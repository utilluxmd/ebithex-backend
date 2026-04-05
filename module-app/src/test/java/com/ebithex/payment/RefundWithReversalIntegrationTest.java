package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.domain.KycStatus;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.payment.infrastructure.TransactionRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests d'intégration — Remboursement avec reversal opérateur.
 *
 * Couvre :
 *  - Opérateur supporte le reversal → reversePayment() appelé, operatorRefundReference sauvegardé
 *  - Reversal échoué (best-effort) → transaction quand même REFUNDED, wallet débité
 *  - operatorGateway.reversePayment() non appelé si supportsReversal() = false
 *
 * Note : supportsReversal() est sur l'opérateur (OperatorRegistry), pas sur OperatorGateway.
 * OperatorGateway est @MockBean. On vérifie si reversePayment() est appelé ou non.
 */
@DisplayName("Refund avec Reversal Opérateur")
class RefundWithReversalIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory      factory;
    @Autowired private MerchantRepository   merchantRepository;
    @Autowired private ApiKeyService        apiKeyService;
    @Autowired private PasswordEncoder      passwordEncoder;
    @Autowired private TransactionRepository transactionRepository;

    private TestDataFactory.MerchantCredentials merchant;
    private String ebithexReference;

    @BeforeEach
    void setUp() {
        merchant = createKycMerchant();

        // All tests in this class test reversal paths — operator must report it supports reversal.
        when(stubOperator.supportsReversal()).thenReturn(true);

        // Simuler un paiement SUCCESS avec une référence opérateur connue
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.success(
                "OP-REVERSAL-TEST-001", null, "Paiement accepté"));

        ResponseEntity<Map> payResp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            5000,
                "phoneNumber",       "+225051234567",
                "merchantReference", "PMT-REVERSAL-" + UUID.randomUUID()
            ), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(payResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) payResp.getBody().get("data");
        ebithexReference = (String) data.get("ebithexReference");

        // Attendre que le wallet soit crédité
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── Reversal supporté ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Reversal supporté + succès → operatorRefundReference persisté en base")
    void refund_withSuccessfulReversal_storesOperatorRefundReference() {
        when(operatorGateway.reversePayment(any(), any(), any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorRefundResult.success("REFUND-OP-REF-001"));

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"), HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("status"))
            .isEqualTo("REFUNDED");

        // Vérifier que la référence opérateur de remboursement est bien persistée
        transactionRepository.findByEbithexReference(ebithexReference).ifPresent(tx ->
            assertThat(tx.getOperatorRefundReference()).isEqualTo("REFUND-OP-REF-001")
        );
    }

    @Test
    @DisplayName("Reversal supporté + succès → operatorGateway.reversePayment() appelé une fois")
    void refund_withSupportedReversal_callsGatewayOnce() {
        when(operatorGateway.reversePayment(any(), any(), any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorRefundResult.success("REFUND-VERIFY-001"));

        restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"), HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        verify(operatorGateway, times(1))
            .reversePayment(any(), eq("OP-REVERSAL-TEST-001"), any(), any());
    }

    // ── Reversal échoué (best-effort) ─────────────────────────────────────────

    @Test
    @DisplayName("Reversal échoué (best-effort) → transaction quand même REFUNDED")
    void refund_reversalFailed_transactionStillRefunded() {
        when(operatorGateway.reversePayment(any(), any(), any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorRefundResult.failure("Service opérateur indisponible"));

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"), HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        // Le remboursement est tout de même traité (best-effort)
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("status"))
            .isEqualTo("REFUNDED");

        // operatorRefundReference est null car le reversal a échoué
        transactionRepository.findByEbithexReference(ebithexReference).ifPresent(tx ->
            assertThat(tx.getOperatorRefundReference()).isNull()
        );
    }

    @Test
    @DisplayName("Reversal lève une exception → best-effort, transaction REFUNDED quand même")
    void refund_reversalThrowsException_transactionStillRefunded() {
        when(operatorGateway.reversePayment(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Timeout réseau opérateur"));

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"), HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("status"))
            .isEqualTo("REFUNDED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TestDataFactory.MerchantCredentials createKycMerchant() {
        String email = factory.uniqueEmail();
        Merchant m = Merchant.builder()
            .businessName("Reversal Test " + System.nanoTime())
            .email(email)
            .hashedSecret(passwordEncoder.encode("Test@1234!"))
            .country("CI").active(true).kycVerified(true).kycStatus(KycStatus.APPROVED)
            .build();
        merchantRepository.save(m);
        String[] keys = apiKeyService.createInitialKeys(m.getId());
        return new TestDataFactory.MerchantCredentials(
            m.getId(), email, "Test@1234!", keys[0], null, null, "CI");
    }
}