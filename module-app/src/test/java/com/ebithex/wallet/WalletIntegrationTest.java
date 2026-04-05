package com.ebithex.wallet;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.wallet.application.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration — Wallet (solde, mouvements, idempotence des opérations).
 *
 * Couvre :
 *  - Solde initial à zéro (getOrCreate)
 *  - Crédit sur paiement réussi (via événement applicatif)
 *  - Débit + pending sur payout initié
 *  - Remboursement sur payout échoué
 *  - Idempotence des mouvements wallet
 *  - Isolation multi-tenant
 */
@DisplayName("Wallet — Solde, Crédit/Débit, Idempotence")
class WalletIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private WalletService walletService;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerMerchant(restTemplate, url(""));
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /wallet/balance?currency=XOF → solde initial 0 (wallet créé à la demande)")
    void getWallet_newMerchant_zeroBalance() {
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/wallet/balance?currency=XOF"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(new BigDecimal(data.get("availableBalance").toString()))
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(new BigDecimal(data.get("pendingBalance").toString()))
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("GET /wallet → retourne la liste des wallets (peut être vide)")
    void getWallet_returnsList() {
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/wallet"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // data est une liste (peut être vide pour un nouveau marchand sans transactions)
        assertThat(response.getBody().get("data")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("GET /wallet sans auth → 401")
    void getWallet_noAuth_returns401() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/v1/wallet"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Isolement : wallet d'un autre marchand ne contamine pas le nôtre")
    void getWallet_isolation_onlyOwnWallet() {
        TestDataFactory.MerchantCredentials other = factory.registerMerchant(restTemplate, url(""));

        // Créditer le wallet de l'autre marchand directement
        walletService.creditPayment(other.merchantId(), new BigDecimal("5000"), "EBITHEX-TEST-ISOLATION", Currency.XOF);

        // Notre marchand voit son propre wallet — toujours à 0
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/wallet/balance?currency=XOF"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(new BigDecimal(data.get("availableBalance").toString()))
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Crédit via événement paiement ─────────────────────────────────────────

    @Test
    @DisplayName("Paiement SUCCESS → wallet crédité du montant net")
    void creditPayment_onSuccessEvent_walletUpdated() {
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(new MobileMoneyOperator.OperatorInitResponse(
                "OP-CREDIT-001", TransactionStatus.SUCCESS, null, null, "Paiement confirmé"));

        Map<String, Object> body = Map.of(
            "amount", new BigDecimal("10000"),
            "phoneNumber", "+225051234567",
            "merchantReference", "PMT-WALLET-CREDIT-01",
            "description", "Test credit"
        );
        ResponseEntity<Map> payResp = restTemplate.exchange(url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        Map<?, ?> payData = (Map<?, ?>) payResp.getBody().get("data");
        BigDecimal expectedNet = new BigDecimal(payData.get("netAmount").toString());

        waitForAsync();

        ResponseEntity<Map> walletResp = restTemplate.exchange(
            url("/v1/wallet/balance?currency=XOF"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        Map<?, ?> data = (Map<?, ?>) walletResp.getBody().get("data");
        BigDecimal available = new BigDecimal(data.get("availableBalance").toString());
        assertThat(available).isEqualByComparingTo(expectedNet);
    }

    // ── Idempotence des mouvements wallet ─────────────────────────────────────

    @Test
    @DisplayName("creditPayment avec même référence → traité une seule fois (idempotence)")
    void creditPayment_sameReference_processedOnce() {
        UUID merchantId = merchant.merchantId();
        String ref = "EBITHEX-IDEMPOTENT-CREDIT-01";

        walletService.creditPayment(merchantId, new BigDecimal("5000"), ref, Currency.XOF);
        walletService.creditPayment(merchantId, new BigDecimal("5000"), ref, Currency.XOF); // doublon

        BigDecimal balance = walletService.getBalance(merchantId, Currency.XOF).availableBalance();
        assertThat(balance).isEqualByComparingTo(new BigDecimal("5000.00")); // crédité une seule fois
    }

    @Test
    @DisplayName("debitPayout → available diminue, pending augmente")
    void debitPayout_movesFromAvailableToPending() {
        UUID merchantId = merchant.merchantId();
        walletService.creditPayment(merchantId, new BigDecimal("10000"), "EBITHEX-CREDIT-BASE", Currency.XOF);

        walletService.debitPayout(merchantId, new BigDecimal("3000"), "PO-DEBIT-001", Currency.XOF);

        var wallet = walletService.getBalance(merchantId, Currency.XOF);
        assertThat(wallet.availableBalance()).isEqualByComparingTo(new BigDecimal("7000.00"));
        assertThat(wallet.pendingBalance()).isEqualByComparingTo(new BigDecimal("3000.00"));
    }

    @Test
    @DisplayName("refundPayout → pending diminue, available restauré")
    void refundPayout_restoresAvailable() {
        UUID merchantId = merchant.merchantId();
        walletService.creditPayment(merchantId, new BigDecimal("10000"), "EBITHEX-CREDIT-REFUND-BASE", Currency.XOF);
        walletService.debitPayout(merchantId, new BigDecimal("3000"), "PO-REFUND-001", Currency.XOF);

        walletService.refundPayout(merchantId, new BigDecimal("3000"), "PO-REFUND-001-REFUND", Currency.XOF);

        var wallet = walletService.getBalance(merchantId, Currency.XOF);
        assertThat(wallet.availableBalance()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(wallet.pendingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("confirmPayout → pending diminue, available inchangé")
    void confirmPayout_reducesPending() {
        UUID merchantId = merchant.merchantId();
        walletService.creditPayment(merchantId, new BigDecimal("10000"), "EBITHEX-CREDIT-CONFIRM-BASE", Currency.XOF);
        walletService.debitPayout(merchantId, new BigDecimal("3000"), "PO-CONFIRM-001", Currency.XOF);

        walletService.confirmPayout(merchantId, new BigDecimal("3000"), "PO-CONFIRM-001-CONFIRM", Currency.XOF);

        var wallet = walletService.getBalance(merchantId, Currency.XOF);
        assertThat(wallet.availableBalance()).isEqualByComparingTo(new BigDecimal("7000.00"));
        assertThat(wallet.pendingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void waitForAsync() {
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}