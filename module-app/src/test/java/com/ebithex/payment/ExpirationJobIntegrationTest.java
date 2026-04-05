package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.payment.application.TransactionExpirationJob;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Job d'expiration des transactions et payouts.
 *
 * Vérifie :
 *  - Les transactions PENDING expirées passent à EXPIRED
 *  - Les transactions PROCESSING expirées passent à EXPIRED
 *  - Les transactions non expirées (expiresAt dans le futur) ne sont pas touchées
 *  - Les transactions déjà terminales (SUCCESS, FAILED) ne sont pas touchées
 *  - Les payouts PENDING expirés passent à EXPIRED
 *  - Les payouts non expirés ne sont pas touchés
 */
@DisplayName("TransactionExpirationJob — expiration PENDING/PROCESSING")
class ExpirationJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TransactionExpirationJob  expirationJob;
    @Autowired private TransactionRepository     transactionRepository;
    @Autowired private PayoutRepository          payoutRepository;
    @Autowired private TestDataFactory           factory;

    private final List<UUID> txIds  = new ArrayList<>();
    private final List<UUID> poIds  = new ArrayList<>();

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        factory.registerMerchant(restTemplate, url(""));
        merchantId = factory.registerKycVerifiedMerchant().merchantId();
    }

    @AfterEach
    void tearDown() {
        txIds.forEach(transactionRepository::deleteById);
        poIds.forEach(payoutRepository::deleteById);
        txIds.clear();
        poIds.clear();
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Transaction PENDING expirée → statut EXPIRED")
    void pendingExpired_becomesExpired() {
        Transaction tx = saveTransaction(TransactionStatus.PENDING, LocalDateTime.now().minusMinutes(5));

        expirationJob.expireTransactions();

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(updated.getFailureReason()).isNotBlank();
    }

    @Test
    @DisplayName("Transaction PROCESSING expirée → statut EXPIRED")
    void processingExpired_becomesExpired() {
        Transaction tx = saveTransaction(TransactionStatus.PROCESSING, LocalDateTime.now().minusSeconds(30));

        expirationJob.expireTransactions();

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.EXPIRED);
    }

    @Test
    @DisplayName("Transaction PENDING non expirée → statut inchangé")
    void pendingNotExpired_unchanged() {
        Transaction tx = saveTransaction(TransactionStatus.PENDING, LocalDateTime.now().plusHours(1));

        expirationJob.expireTransactions();

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("Transaction SUCCESS → non touchée par le job")
    void successTransaction_notTouched() {
        Transaction tx = saveTransaction(TransactionStatus.SUCCESS, LocalDateTime.now().minusMinutes(10));

        expirationJob.expireTransactions();

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    @DisplayName("Transaction FAILED → non touchée par le job")
    void failedTransaction_notTouched() {
        Transaction tx = saveTransaction(TransactionStatus.FAILED, LocalDateTime.now().minusMinutes(10));

        expirationJob.expireTransactions();

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    // ── Payouts ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Payout PENDING expiré → statut EXPIRED")
    void payoutPendingExpired_becomesExpired() {
        Payout payout = savePayout(TransactionStatus.PENDING, LocalDateTime.now().minusMinutes(5));

        expirationJob.expirePayouts();

        Payout updated = payoutRepository.findById(payout.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(updated.getFailureReason()).isNotBlank();
    }

    @Test
    @DisplayName("Payout PENDING non expiré → statut inchangé")
    void payoutNotExpired_unchanged() {
        Payout payout = savePayout(TransactionStatus.PENDING, LocalDateTime.now().plusHours(2));

        expirationJob.expirePayouts();

        Payout updated = payoutRepository.findById(payout.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction saveTransaction(TransactionStatus status, LocalDateTime expiresAt) {
        Transaction tx = Transaction.builder()
            .ebithexReference("AP-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
            .merchantReference("REF-" + System.nanoTime())
            .merchantId(merchantId)
            .amount(new BigDecimal("1000.00"))
            .feeAmount(new BigDecimal("10.00"))
            .netAmount(new BigDecimal("990.00"))
            .currency(Currency.XOF)
            .phoneNumber("encrypted_phone_placeholder")
            .operator(OperatorType.MTN_MOMO_CI)
            .status(status)
            .expiresAt(expiresAt)
            .build();
        tx = transactionRepository.save(tx);
        txIds.add(tx.getId());
        return tx;
    }

    private Payout savePayout(TransactionStatus status, LocalDateTime expiresAt) {
        Payout payout = Payout.builder()
            .ebithexReference("PO-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
            .merchantReference("PO-REF-" + System.nanoTime())
            .merchantId(merchantId)
            .amount(new BigDecimal("5000.00"))
            .feeAmount(new BigDecimal("25.00"))
            .netAmount(new BigDecimal("4975.00"))
            .currency(Currency.XOF)
            .phoneNumber("encrypted_phone_placeholder")
            .operator(OperatorType.MTN_MOMO_CI)
            .status(status)
            .expiresAt(expiresAt)
            .build();
        payout = payoutRepository.save(payout);
        poIds.add(payout.getId());
        return payout;
    }
}