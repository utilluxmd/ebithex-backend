package com.ebithex.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.payment.domain.FeeRule;
import com.ebithex.payment.infrastructure.FeeRuleRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.security.EbithexPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Règles tarifaires dynamiques.
 *
 * Couvre :
 *  - CRUD via back-office (SUPER_ADMIN peut tout, ADMIN lecture seule)
 *  - Calcul des frais : règle par opérateur appliquée au paiement
 *  - Override marchand : règle spécifique > règle opérateur
 *  - Résolution de priorité : règle haute priorité gagne
 *  - Frais plafonnés : maxFee respecté
 *  - Règle inactive : ignorée lors du calcul
 */
@DisplayName("Règles tarifaires — CRUD et calcul dynamique des frais")
class FeeRuleIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory    factory;
    @Autowired private FeeRuleRepository  feeRuleRepository;
    @Autowired private ObjectMapper       objectMapper;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerKycVerifiedMerchant();
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.success(
                "OP-FEE-" + System.nanoTime(), null, "OK"));
    }

    // ── CRUD — contrôle d'accès ───────────────────────────────────────────────

    @Test
    @DisplayName("SUPER_ADMIN peut créer une règle tarifaire")
    void create_superAdmin_returns200() throws Exception {
        String body = """
            {
              "name": "MTN-Custom-10pct",
              "feeType": "PERCENTAGE",
              "operator": "MTN_MOMO_CI",
              "percentageRate": 10.0,
              "priority": 50,
              "active": true
            }
            """;

        mockMvc.perform(post("/api/internal/config/fee-rules")
                .with(authentication(auth(factory.superAdminPrincipal())))
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("MTN-Custom-10pct"))
            .andExpect(jsonPath("$.data.feeType").value("PERCENTAGE"))
            .andExpect(jsonPath("$.data.id").isNotEmpty());
    }

    @Test
    @DisplayName("ADMIN ne peut pas créer une règle tarifaire → 403")
    void create_admin_returns403() throws Exception {
        String body = """
            {
              "name": "Forbidden Rule",
              "feeType": "PERCENTAGE",
              "percentageRate": 5.0,
              "active": true
            }
            """;

        mockMvc.perform(post("/api/internal/config/fee-rules")
                .with(authentication(auth(factory.adminPrincipal())))
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN peut lister les règles tarifaires → 200")
    void list_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/internal/config/fee-rules")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("SUPER_ADMIN peut modifier une règle tarifaire")
    void update_superAdmin_returns200() throws Exception {
        // Créer une règle directement en DB
        FeeRule rule = feeRuleRepository.save(buildPercentageRule("Update-Test", null, null, new BigDecimal("2.0"), 10));

        String updateBody = """
            {
              "name": "Update-Test-Modified",
              "feeType": "PERCENTAGE",
              "percentageRate": 3.0,
              "priority": 10,
              "active": true
            }
            """;

        mockMvc.perform(put("/api/internal/config/fee-rules/" + rule.getId())
                .with(authentication(auth(factory.superAdminPrincipal())))
                .contentType(APPLICATION_JSON)
                .content(updateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Update-Test-Modified"))
            .andExpect(jsonPath("$.data.percentageRate").value(3.0));
    }

    @Test
    @DisplayName("SUPER_ADMIN peut désactiver une règle (soft delete)")
    void deactivate_superAdmin_returns200() throws Exception {
        FeeRule rule = feeRuleRepository.save(buildPercentageRule("Delete-Test", null, null, new BigDecimal("1.0"), 5));

        mockMvc.perform(delete("/api/internal/config/fee-rules/" + rule.getId())
                .with(authentication(auth(factory.superAdminPrincipal()))))
            .andExpect(status().isOk());

        // Vérifier que la règle est désactivée (soft delete)
        FeeRule deactivated = feeRuleRepository.findById(rule.getId()).orElseThrow();
        assertThat(deactivated.isActive()).isFalse();
    }

    @Test
    @DisplayName("GET /fee-rules/{id} avec ID inexistant → 400 FEE_RULE_NOT_FOUND")
    void get_notFound_returns400() throws Exception {
        mockMvc.perform(get("/api/internal/config/fee-rules/" + UUID.randomUUID())
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("FEE_RULE_NOT_FOUND"));
    }

    // ── Calcul des frais ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Paiement MTN_CI utilise le taux de la règle opérateur MTN_CI")
    void payment_usesMtnOperatorFeeRule() {
        // Désactiver les règles par défaut pour MTN_CI et créer une règle précise
        feeRuleRepository.findAll().stream()
            .filter(r -> r.getOperator() == OperatorType.MTN_MOMO_CI && r.getMerchantId() == null)
            .forEach(r -> { r.setActive(false); feeRuleRepository.save(r); });

        feeRuleRepository.save(buildPercentageRule("MTN-CI-2pct", null, OperatorType.MTN_MOMO_CI,
            new BigDecimal("2.0"), 20));

        // Initier un paiement MTN (numéro +225 05... → MTN_CI)
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            new java.math.BigDecimal("10000"),
                "phoneNumber",       "+225051234567",
                "merchantReference", "FEE-MTN-" + UUID.randomUUID()
            ), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");

        // 2% de 10000 = 200
        assertThat(new BigDecimal(data.get("feeAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(new BigDecimal(data.get("netAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("9800.00"));
    }

    @Test
    @DisplayName("Règle marchand-spécifique prime sur la règle opérateur générale")
    void payment_merchantSpecificRuleOverridesOperatorRule() {
        // Règle opérateur générale : 5%
        feeRuleRepository.findAll().stream()
            .filter(r -> r.getOperator() == OperatorType.ORANGE_MONEY_CI && r.getMerchantId() == null)
            .forEach(r -> { r.setActive(false); feeRuleRepository.save(r); });

        feeRuleRepository.save(buildPercentageRule("ORANGE-CI-5pct", null, OperatorType.ORANGE_MONEY_CI,
            new BigDecimal("5.0"), 10));

        // Règle marchand spécifique : 1% — doit primer
        feeRuleRepository.save(buildPercentageRule("ORANGE-CI-Merchant-1pct",
            merchant.merchantId(), OperatorType.ORANGE_MONEY_CI,
            new BigDecimal("1.0"), 10));

        // Paiement Orange (numéro +225 04... → ORANGE_CI)
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            new java.math.BigDecimal("10000"),
                "phoneNumber",       "+225041234567",
                "merchantReference", "FEE-ORANGE-" + UUID.randomUUID()
            ), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");

        // La règle marchand (1%) doit s'appliquer → frais = 100, net = 9900
        assertThat(new BigDecimal(data.get("feeAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(new BigDecimal(data.get("netAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("9900.00"));
    }

    @Test
    @DisplayName("maxFee respecté : frais plafonné même si le taux est élevé")
    void payment_maxFeeCapApplied() {
        // Règle 10% avec maxFee = 300 XOF
        feeRuleRepository.findAll().stream()
            .filter(r -> r.getOperator() == OperatorType.WAVE_CI && r.getMerchantId() == null)
            .forEach(r -> { r.setActive(false); feeRuleRepository.save(r); });

        FeeRule capRule = buildPercentageRule("WAVE-CI-capped", null, OperatorType.WAVE_CI,
            new BigDecimal("10.0"), 30);
        capRule.setMaxFee(new BigDecimal("300.00"));
        feeRuleRepository.save(capRule);

        // 10% de 10000 = 1000, mais maxFee = 300 → frais = 300
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            new java.math.BigDecimal("10000"),
                "phoneNumber",       "+225021234567",   // WAVE_CI
                "merchantReference", "FEE-WAVE-CAP-" + UUID.randomUUID()
            ), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");

        assertThat(new BigDecimal(data.get("feeAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(new BigDecimal(data.get("netAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("9700.00"));
    }

    @Test
    @DisplayName("Règle inactive ignorée — la règle de fallback s'applique")
    void payment_inactiveRuleIgnored_fallbackApplies() {
        // Désactiver toutes les règles spécifiques à MTN_CI
        feeRuleRepository.findAll().stream()
            .filter(r -> r.getOperator() == OperatorType.MTN_MOMO_CI)
            .forEach(r -> { r.setActive(false); feeRuleRepository.save(r); });

        // Créer une règle inactive (ne doit pas être utilisée)
        FeeRule inactive = buildPercentageRule("MTN-CI-Inactive", null, OperatorType.MTN_MOMO_CI,
            new BigDecimal("99.0"), 100);
        inactive.setActive(false);
        feeRuleRepository.save(inactive);

        // Le fallback global doit prendre le relais (1.5% par défaut via V4 migration)
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            new java.math.BigDecimal("10000"),
                "phoneNumber",       "+225059876543",
                "merchantReference", "FEE-MTN-INACTIVE-" + UUID.randomUUID()
            ), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");

        // La règle inactive (99%) ne doit pas être appliquée — frais < 9900
        BigDecimal feeAmount = new BigDecimal(data.get("feeAmount").toString());
        assertThat(feeAmount).isLessThan(new BigDecimal("9000.00"));
    }

    @Test
    @DisplayName("Règle FLAT : frais fixe indépendant du montant")
    void payment_flatFeeRule_appliesFixedAmount() {
        feeRuleRepository.findAll().stream()
            .filter(r -> r.getOperator() == OperatorType.MTN_MOMO_CI && r.getMerchantId() == null)
            .forEach(r -> { r.setActive(false); feeRuleRepository.save(r); });

        FeeRule flatRule = new FeeRule();
        flatRule.setName("MTN-CI-FLAT-500");
        flatRule.setFeeType(FeeRule.FeeType.FLAT);
        flatRule.setOperator(OperatorType.MTN_MOMO_CI);
        flatRule.setFlatAmount(new BigDecimal("500.00"));
        flatRule.setPriority(50);
        flatRule.setActive(true);
        feeRuleRepository.save(flatRule);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            new java.math.BigDecimal("50000"),
                "phoneNumber",       "+225051112221",
                "merchantReference", "FEE-MTN-FLAT-" + UUID.randomUUID()
            ), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");

        assertThat(new BigDecimal(data.get("feeAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(new BigDecimal(data.get("netAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("49500.00"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FeeRule buildPercentageRule(String name, UUID merchantId,
                                        OperatorType operator, BigDecimal rate, int priority) {
        FeeRule rule = new FeeRule();
        rule.setName(name);
        rule.setFeeType(FeeRule.FeeType.PERCENTAGE);
        rule.setMerchantId(merchantId);
        rule.setOperator(operator);
        rule.setPercentageRate(rate);
        rule.setPriority(priority);
        rule.setActive(true);
        return rule;
    }

    private UsernamePasswordAuthenticationToken auth(EbithexPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}