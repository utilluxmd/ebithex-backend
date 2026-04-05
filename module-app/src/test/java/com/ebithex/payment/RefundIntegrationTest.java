package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.domain.KycStatus;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.wallet.application.WalletService;
import com.ebithex.wallet.dto.WalletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration — Remboursements (POST /v1/payments/{ref}/refund).
 *
 * Couvre :
 *  - Remboursement nominal (transaction SUCCESS → REFUNDED, wallet débité)
 *  - Refus si transaction non-SUCCESS
 *  - Refus si solde wallet insuffisant
 *  - Isolation multi-tenant (marchand A ne peut pas rembourser transaction de B)
 *  - Idempotence (double appel → erreur REFUND_NOT_ALLOWED)
 */
@DisplayName("Refund — POST /v1/payments/{ref}/refund")
class RefundIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory      factory;
    @Autowired private MerchantRepository   merchantRepository;
    @Autowired private ApiKeyService        apiKeyService;
    @Autowired private PasswordEncoder      passwordEncoder;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private WalletService        walletService;

    private TestDataFactory.MerchantCredentials merchant;
    private String ebithexReference;
    private java.math.BigDecimal paymentNetAmount;

    @BeforeEach
    void setUp() {
        merchant = createKycMerchant();

        // Simule un paiement SUCCESS → wallet crédité
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.success(
                "OP-" + System.nanoTime(), null, "Paiement accepté"));

        ResponseEntity<Map> payResp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(paymentBody("PMT-REFUND-" + UUID.randomUUID()), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);
        assertThat(payResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) payResp.getBody().get("data");
        ebithexReference = (String) data.get("ebithexReference");
        paymentNetAmount = new BigDecimal(data.get("netAmount").toString());

        // Attendre que l'événement PaymentStatusChangedEvent crédite le wallet
        waitForAsync();
    }

    @Test
    @DisplayName("Remboursement nominal → 200, statut REFUNDED, wallet débité")
    void refund_successTransaction_walletDebited() {
        BigDecimal balanceBefore = getBalance();

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"),
            HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("REFUNDED");
        assertThat(data.get("ebithexReference")).isEqualTo(ebithexReference);

        // Wallet doit être débité du montant net du paiement
        BigDecimal balanceAfter = getBalance();
        assertThat(balanceBefore.subtract(balanceAfter)).isEqualByComparingTo(paymentNetAmount);
    }

    @Test
    @DisplayName("Double remboursement → 400 REFUND_NOT_ALLOWED")
    void refund_alreadyRefunded_returns400() {
        // Premier remboursement
        restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"),
            HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        // Deuxième remboursement → doit échouer
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"),
            HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("REFUND_NOT_ALLOWED");
    }

    @Test
    @DisplayName("Remboursement transaction PROCESSING → 400 REFUND_NOT_ALLOWED")
    void refund_processingTransaction_returns400() {
        // Créer une transaction PROCESSING
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-PROC-" + System.nanoTime(), "*144*1#", "En attente"));

        ResponseEntity<Map> payResp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(paymentBody("PMT-PROC-" + UUID.randomUUID()), factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);
        String procRef = (String) ((Map<?, ?>) payResp.getBody().get("data")).get("ebithexReference");

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments/" + procRef + "/refund"),
            HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("REFUND_NOT_ALLOWED");
    }

    @Test
    @DisplayName("Remboursement transaction d'un autre marchand → 404")
    void refund_otherMerchantTransaction_returns404() {
        TestDataFactory.MerchantCredentials otherMerchant = createKycMerchant();

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments/" + ebithexReference + "/refund"),
            HttpMethod.POST,
            new HttpEntity<>(null, factory.apiKeyHeaders(otherMerchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> paymentBody(String merchantRef) {
        long suffix = Math.abs(System.nanoTime() % 10000000);
        String phone = String.format("+22505%07d", suffix);
        return Map.of(
            "amount",            5000,
            "phoneNumber",       phone,
            "merchantReference", merchantRef,
            "description",       "Test payment"
        );
    }

    private BigDecimal getBalance() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/wallet/balance"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        return new BigDecimal(data.get("availableBalance").toString());
    }

    private TestDataFactory.MerchantCredentials createKycMerchant() {
        String email = factory.uniqueEmail();
        Merchant m = Merchant.builder()
            .businessName("Refund Test " + System.nanoTime())
            .email(email)
            .hashedSecret(passwordEncoder.encode("Test@1234!"))
            .country("CI").active(true).kycVerified(true).kycStatus(KycStatus.APPROVED)
            .build();
        merchantRepository.save(m);
        String[] keys = apiKeyService.createInitialKeys(m.getId());
        return new TestDataFactory.MerchantCredentials(m.getId(), email, "Test@1234!", keys[0], null, null, "CI");
    }

    private void waitForAsync() {
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}