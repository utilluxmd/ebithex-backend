package com.ebithex.payout;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.operatorfloat.domain.OperatorFloat;
import com.ebithex.operatorfloat.infrastructure.OperatorFloatRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.shared.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration — Décaissements (Payouts).
 *
 * Couvre :
 *  - Initiation d'un payout (nécessite MERCHANT_KYC_VERIFIED)
 *  - Refus si marchand non KYC vérifié
 *  - Idempotence
 *  - Isolation multi-tenant
 *  - Statut payout
 */
@DisplayName("Payout — Initiation, KYC requis, Idempotence, Multitenancy")
class PayoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private OperatorFloatRepository floatRepository;

    private TestDataFactory.MerchantCredentials kycMerchant;

    @BeforeEach
    void setUp() {
        // Marchand KYC vérifié — peut faire des payouts
        kycMerchant = factory.registerKycVerifiedMerchant();

        // Amorcer le float MTN_CI avec un solde suffisant pour tous les tests
        OperatorFloat mtnFloat = floatRepository.findById(OperatorType.MTN_MOMO_CI).orElseGet(() ->
            OperatorFloat.builder()
                .operatorType(OperatorType.MTN_MOMO_CI)
                .balance(BigDecimal.ZERO)
                .lowBalanceThreshold(new BigDecimal("100000"))
                .build());
        mtnFloat.setBalance(new BigDecimal("10000000"));
        floatRepository.save(mtnFloat);

        when(operatorGateway.initiateDisbursement(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "DISBURSE-" + System.nanoTime(), null, "Décaissement initié"));
    }

    // ── Autorisation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Payout sans KYC vérifié → 403")
    void initiatePayout_nonKycMerchant_returns403() throws Exception {
        UUID merchantId = UUID.randomUUID();
        EbithexPrincipal nonKycPrincipal = EbithexPrincipal.builder()
            .id(merchantId)
            .email("nonkyc-payout@test.ebithex.io")
            .roles(Set.of(Role.MERCHANT))
            .active(true)
            .merchantId(merchantId)
            .build();

        mockMvc.perform(post("/api/v1/payouts")
                .with(user(nonKycPrincipal))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 1000, \"phoneNumber\": \"+225051234567\", "
                        + "\"merchantReference\": \"PO-NOKYC-01\", \"beneficiaryName\": \"Test\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Payout sans authentification → 401")
    void initiatePayout_noAuth_returns401() {
        Map<String, Object> body = buildPayoutRequest("PO-NOAUTH", "+225051234567", "1000");
        ResponseEntity<Map> response = restTemplate.postForEntity(url("/v1/payouts"), body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Initiation réussie ────────────────────────────────────────────────────

    @Test
    @DisplayName("Payout avec KYC vérifié → 200, frais 0.5%, montant net correct")
    void initiatePayout_kycMerchant_success() {
        Map<String, Object> body = buildPayoutRequest("PO-SUCCESS-01", "+225051234567", "10000");

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payouts"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(kycMerchant.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PROCESSING");
        assertThat(data.get("ebithexReference")).asString().startsWith("PO-");
        // Frais payout : 0.5% de 10000 = 50 FCFA
        assertThat(new BigDecimal(data.get("feeAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(new BigDecimal(data.get("netAmount").toString()))
            .isEqualByComparingTo(new BigDecimal("9950.00"));
    }

    @Test
    @DisplayName("Payout refusé par l'opérateur → statut FAILED")
    void initiatePayout_operatorFails_statusFailed() {
        when(operatorGateway.initiateDisbursement(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.failed("Numéro invalide"));

        Map<String, Object> body = buildPayoutRequest("PO-FAIL-01", "+225051234567", "5000");

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payouts"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(kycMerchant.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("FAILED");
        assertThat(data.get("failureReason")).isEqualTo("Numéro invalide");
    }

    // ── Idempotence ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Même merchantReference → retourne le même payout (idempotence)")
    void initiatePayout_sameReference_returnsExisting() {
        Map<String, Object> body = buildPayoutRequest("PO-IDEMPOTENT-01", "+225051234567", "5000");
        HttpEntity<Map<String, Object>> request =
            new HttpEntity<>(body, factory.apiKeyHeaders(kycMerchant.apiKey()));

        ResponseEntity<Map> first  = restTemplate.exchange(url("/v1/payouts"), HttpMethod.POST, request, Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(url("/v1/payouts"), HttpMethod.POST, request, Map.class);

        Map<?, ?> firstData  = (Map<?, ?>) first.getBody().get("data");
        Map<?, ?> secondData = (Map<?, ?>) second.getBody().get("data");

        assertThat(firstData.get("ebithexReference"))
            .isEqualTo(secondData.get("ebithexReference"));
    }

    // ── Consultation statut ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /payouts/{ref} → retourne le payout du marchand")
    void getPayoutStatus_ownPayout_returnsDetails() {
        Map<String, Object> body = buildPayoutRequest("PO-GET-01", "+225051234567", "5000");
        ResponseEntity<Map> created = restTemplate.exchange(
            url("/v1/payouts"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(kycMerchant.apiKey())), Map.class);

        String ref = (String) ((Map<?, ?>) created.getBody().get("data")).get("ebithexReference");

        ResponseEntity<Map> status = restTemplate.exchange(
            url("/v1/payouts/" + ref), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(kycMerchant.apiKey())), Map.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) status.getBody().get("data")).get("ebithexReference")).isEqualTo(ref);
    }

    @Test
    @DisplayName("GET /payouts/{ref} d'un autre marchand → 404 (isolation)")
    void getPayoutStatus_otherMerchant_returns404() {
        TestDataFactory.MerchantCredentials other = factory.registerKycVerifiedMerchant();
        Map<String, Object> body = buildPayoutRequest("PO-ISOLATED-01", "+225051234567", "5000");
        ResponseEntity<Map> created = restTemplate.exchange(
            url("/v1/payouts"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(other.apiKey())), Map.class);

        String ref = (String) ((Map<?, ?>) created.getBody().get("data")).get("ebithexReference");

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payouts/" + ref), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(kycMerchant.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildPayoutRequest(String ref, String phone, String amount) {
        return Map.of(
            "amount",            new BigDecimal(amount),
            "phoneNumber",       phone,
            "merchantReference", ref,
            "beneficiaryName",   "Test Beneficiary",
            "description",       "Test payout"
        );
    }
}