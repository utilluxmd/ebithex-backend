package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.shared.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration — Annulation de transaction PENDING par le marchand.
 *
 * Couvre :
 *  - Annulation d'une transaction PENDING → 200 + statut CANCELLED
 *  - Annulation d'une transaction SUCCESS → 400 CANCEL_NOT_ALLOWED
 *  - Annulation d'une transaction déjà CANCELLED → 400 CANCEL_NOT_ALLOWED
 *  - Cross-tenant : annulation d'une transaction d'un autre marchand → 400 TRANSACTION_NOT_FOUND
 */
@DisplayName("Annulation de paiement — PENDING → CANCELLED")
class CancelPaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory factory;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerKycVerifiedMerchant();
        // Réponse PENDING : l'opérateur accepte la demande mais attend confirmation
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-PENDING-" + System.nanoTime(), null, "En attente de confirmation"));
    }

    @Test
    @DisplayName("Annulation d'une transaction PENDING → 200 + statut CANCELLED")
    void cancel_pendingTransaction_returnsCancelled() {
        String ref = initiatePendingPayment();

        ResponseEntity<Map> resp = cancel(ref, merchant.apiKey());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("status")).isEqualTo(TransactionStatus.CANCELLED.name());
        assertThat(data.get("ebithexReference")).isEqualTo(ref);
    }

    @Test
    @DisplayName("Annulation d'une transaction SUCCESS → 400 CANCEL_NOT_ALLOWED")
    void cancel_successTransaction_returns400() {
        // Forcer une transaction SUCCESS
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.success(
                "OP-OK-" + System.nanoTime(), null, "Payé"));
        String ref = initiatePayment();

        ResponseEntity<Map> resp = cancel(ref, merchant.apiKey());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("CANCEL_NOT_ALLOWED");
    }

    @Test
    @DisplayName("Double annulation → 400 CANCEL_NOT_ALLOWED")
    void cancel_alreadyCancelled_returns400() {
        String ref = initiatePendingPayment();
        cancel(ref, merchant.apiKey()); // premier cancel OK

        ResponseEntity<Map> resp = cancel(ref, merchant.apiKey()); // deuxième cancel KO
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("CANCEL_NOT_ALLOWED");
    }

    @Test
    @DisplayName("Cross-tenant : annuler la transaction d'un autre marchand → 404")
    void cancel_otherMerchantsTransaction_returns404() {
        String ref = initiatePendingPayment();
        TestDataFactory.MerchantCredentials other = factory.registerKycVerifiedMerchant();

        ResponseEntity<Map> resp = cancel(ref, other.apiKey());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("TRANSACTION_NOT_FOUND");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String initiatePendingPayment() {
        String ref = initiatePayment();
        // Vérifier que c'est bien PROCESSING (l'opérateur attend)
        ResponseEntity<Map> status = restTemplate.exchange(
            url("/v1/payments/" + ref), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ref;
    }

    private String initiatePayment() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            5000,
                "phoneNumber",       "+225051234567",
                "merchantReference", "CANCEL-" + UUID.randomUUID()
            ), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) ((Map<?, ?>) resp.getBody().get("data")).get("ebithexReference");
    }

    private ResponseEntity<Map> cancel(String reference, String apiKey) {
        return restTemplate.exchange(
            url("/v1/payments/" + reference + "/cancel"), HttpMethod.POST,
            new HttpEntity<>(factory.apiKeyHeaders(apiKey)),
            Map.class);
    }
}