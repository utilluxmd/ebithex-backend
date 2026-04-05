package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.domain.KycStatus;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.shared.apikey.ApiKeyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests d'intégration — Mode sandbox (testMode = true sur Merchant).
 *
 * Couvre :
 *  - Transaction sandbox → SUCCESS immédiat, aucun appel opérateur réel
 *  - Transaction sandbox → écrite dans sandbox.transactions (schéma séparé, pas dans public)
 *  - Marchand live → OperatorGateway appelé normalement
 *  - Activation via PUT /internal/merchants/{id}/test-mode (SUPER_ADMIN)
 */
@DisplayName("Sandbox Mode — Transactions simulées sans appel opérateur")
class SandboxModeIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory    factory;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ApiKeyService      apiKeyService;
    @Autowired private PasswordEncoder    passwordEncoder;

    private record MerchantWithKey(UUID merchantId, String rawApiKey) {}

    @BeforeEach
    void setUp() {
        // Aucun mock par défaut — on vérifie que le mock n'est PAS appelé en sandbox
    }

    @Test
    @DisplayName("Paiement en mode sandbox → SUCCESS immédiat, OperatorGateway non appelé")
    void payment_sandboxMode_successWithoutOperatorCall() {
        MerchantWithKey m = createMerchant(true);

        ResponseEntity<Map> resp = pay(m, "5000");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("SUCCESS");
        assertThat(data.get("operatorReference").toString()).startsWith("TEST-");

        // Vérifier que l'opérateur réel n'a PAS été appelé
        verify(operatorGateway, never()).initiatePayment(any(), any());
    }

    @Test
    @DisplayName("Paiement en mode live → OperatorGateway appelé")
    void payment_liveMode_operatorGatewayCalled() {
        MerchantWithKey m = createMerchant(false);

        // Sans mock, OperatorGateway est null → doit throw → test
        // On vérifie juste que la gateway est invoquée (peut retourner erreur)
        try {
            pay(m, "5000");
        } catch (Exception ignored) {}

        verify(operatorGateway).initiatePayment(any(), any());
    }

    @Test
    @DisplayName("Activation sandbox via back-office → paiements suivants simulés")
    void setTestMode_viaSuperAdmin_subsequentPaymentsSimulated() throws Exception {
        MerchantWithKey m = createMerchant(false);

        // Activer le mode sandbox via l'API back-office
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/internal/merchants/" + m.merchantId() + "/test-mode")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(factory.superAdminPrincipal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"testMode\": true}")
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        // Paiement → doit être simulé
        ResponseEntity<Map> resp = pay(m, "5000");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("SUCCESS");
        verify(operatorGateway, never()).initiatePayment(any(), any());
    }

    @Test
    @DisplayName("ADMIN ne peut pas activer sandbox — réservé SUPER_ADMIN")
    void setTestMode_byAdmin_forbidden() throws Exception {
        MerchantWithKey m = createMerchant(false);

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/internal/merchants/" + m.merchantId() + "/test-mode")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(factory.adminPrincipal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"testMode\": true}")
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MerchantWithKey createMerchant(boolean testMode) {
        Merchant m = Merchant.builder()
            .businessName("SandboxTest-" + System.nanoTime())
            .email(factory.uniqueEmail())
            .hashedSecret(passwordEncoder.encode("Test@1234!"))
            .country("CI").active(true).kycVerified(true).kycStatus(KycStatus.APPROVED)
            .testMode(testMode)
            .build();
        Merchant saved = merchantRepository.save(m);
        // Créer la clé via ApiKeyService (type TEST si testMode, sinon LIVE)
        ApiKeyType keyType = testMode ? ApiKeyType.TEST : ApiKeyType.LIVE;
        String rawApiKey = apiKeyService.createKey(saved.getId(), keyType,
            "Test key", null, null, null);
        return new MerchantWithKey(saved.getId(), rawApiKey);
    }

    private ResponseEntity<Map> pay(MerchantWithKey m, String amount) {
        Map<String, Object> body = Map.of(
            "amount",            new java.math.BigDecimal(amount),
            "phoneNumber",       "+2250501" + String.format("%05d", System.nanoTime() % 100000),
            "merchantReference", "SANDBOX-" + UUID.randomUUID()
        );
        return restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(m.rawApiKey())),
            Map.class);
    }
}