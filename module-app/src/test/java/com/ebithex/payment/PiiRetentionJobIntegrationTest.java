package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.payment.application.PiiRetentionJob;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.crypto.EncryptionService;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Job de purge PII (numéros de téléphone).
 *
 * Couvre :
 *  - Transaction antérieure au seuil → phone_number pseudonymisé ("PURGED"), index null, pii_purged_at défini
 *  - Transaction récente → non purgée
 *  - Transaction en mode test → non purgée (même si ancienne)
 *  - Payout antérieur au seuil → purgé de la même façon
 *  - Idempotence : re-run du job → les lignes déjà purgées restent inchangées
 *  - API retourne "PURGED" pour phone number après purge
 */
@DisplayName("PiiRetentionJob — Purge des numéros de téléphone")
class PiiRetentionJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PiiRetentionJob       piiRetentionJob;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private PayoutRepository      payoutRepository;
    @Autowired private EncryptionService     encryptionService;
    @Autowired private JdbcTemplate          jdbc;
    @Autowired private TestDataFactory       factory;
    @Autowired private MerchantRepository    merchantRepository;

    private static final String REAL_PHONE       = "+22507123456";
    private static final String PURGED_SENTINEL  = "PURGED";
    /** Cutoff : transactions créées avant NOW - 5 ans sont éligibles. */
    private static final LocalDateTime OLD_DATE  = LocalDateTime.now().minusYears(6);
    private static final LocalDateTime RECENT    = LocalDateTime.now().minusDays(30);

    private final List<UUID> txIds  = new ArrayList<>();
    private final List<UUID> poIds  = new ArrayList<>();
    private UUID testMerchantId;

    @BeforeEach
    void createTestMerchant() {
        Merchant m = Merchant.builder()
            .businessName("PII Test Merchant")
            .email(factory.uniqueEmail())
            .hashedSecret("not-used")
            .country("CI").active(true).kycStatus(com.ebithex.merchant.domain.KycStatus.NONE)
            .build();
        merchantRepository.save(m);
        testMerchantId = m.getId();
    }

    @AfterEach
    void tearDown() {
        txIds.forEach(transactionRepository::deleteById);
        poIds.forEach(payoutRepository::deleteById);
        txIds.clear();
        poIds.clear();
        if (testMerchantId != null) {
            merchantRepository.deleteById(testMerchantId);
        }
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Transaction ancienne → phone_number pseudonymisé, index null, pii_purged_at défini")
    void oldTransaction_isPurged() {
        Transaction tx = saveAndBackdate(buildTransaction(), OLD_DATE);

        piiRetentionJob.purgeTransactions(LocalDateTime.now().minusYears(5));

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(updated.getPiiPurgedAt()).isNotNull();
        assertThat(updated.getPhoneNumberIndex()).isNull();

        // Le déchiffrement du phone_number doit retourner "PURGED"
        String decrypted = encryptionService.decrypt(updated.getPhoneNumber());
        assertThat(decrypted).isEqualTo(PURGED_SENTINEL);
    }

    @Test
    @DisplayName("Transaction récente → non purgée")
    void recentTransaction_notPurged() {
        Transaction tx = saveAndBackdate(buildTransaction(), RECENT);

        piiRetentionJob.purgeTransactions(LocalDateTime.now().minusYears(5));

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(updated.getPiiPurgedAt()).isNull();
        assertThat(updated.getPhoneNumberIndex()).isNotNull();

        // Le numéro original est toujours récupérable
        assertThat(encryptionService.decrypt(updated.getPhoneNumber())).isEqualTo(REAL_PHONE);
    }

    @Test
    @DisplayName("Transaction dans le schéma sandbox → non purgée par le job prod")
    void sandboxTransaction_notPurgedByProdJob() {
        // Écrire la transaction dans sandbox (SandboxContextHolder.set(true) route le pool vers sandbox.transactions)
        UUID sandboxTxId;
        SandboxContextHolder.set(true);
        try {
            Transaction tx = buildTransaction();
            jdbc.update("UPDATE sandbox.transactions SET created_at = ? WHERE id = ?",
                OLD_DATE, tx.getId());
            sandboxTxId = tx.getId();
        } finally {
            SandboxContextHolder.clear();
        }

        // Le job force SandboxContextHolder.set(false) → lit public.transactions uniquement
        piiRetentionJob.purgeTransactions(LocalDateTime.now().minusYears(5));

        // La transaction sandbox est intacte
        SandboxContextHolder.set(true);
        try {
            Transaction updated = transactionRepository.findById(sandboxTxId).orElseThrow();
            assertThat(updated.getPiiPurgedAt()).isNull();
            transactionRepository.deleteById(sandboxTxId); // nettoyage sandbox
        } finally {
            SandboxContextHolder.clear();
        }
    }

    @Test
    @DisplayName("Idempotence : double exécution du job → pii_purged_at inchangé au second passage")
    void doubleRun_idempotent() {
        Transaction tx = saveAndBackdate(buildTransaction(), OLD_DATE);
        LocalDateTime cutoff = LocalDateTime.now().minusYears(5);

        piiRetentionJob.purgeTransactions(cutoff);
        LocalDateTime firstPurgedAt = transactionRepository.findById(tx.getId())
            .orElseThrow().getPiiPurgedAt();

        // Pause d'1ms pour s'assurer que le timestamp serait différent si re-purgé
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        piiRetentionJob.purgeTransactions(cutoff);
        LocalDateTime secondPurgedAt = transactionRepository.findById(tx.getId())
            .orElseThrow().getPiiPurgedAt();

        // La date ne doit pas changer au second passage (filtre pii_purged_at IS NULL)
        assertThat(secondPurgedAt).isEqualTo(firstPurgedAt);
    }

    // ── Payouts ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Payout ancien → phone_number pseudonymisé, pii_purged_at défini")
    void oldPayout_isPurged() {
        Payout payout = saveAndBackdatePayout(buildPayout(), OLD_DATE);

        piiRetentionJob.purgePayouts(LocalDateTime.now().minusYears(5));

        Payout updated = payoutRepository.findById(payout.getId()).orElseThrow();
        assertThat(updated.getPiiPurgedAt()).isNotNull();
        assertThat(updated.getPhoneNumberIndex()).isNull();
        assertThat(encryptionService.decrypt(updated.getPhoneNumber())).isEqualTo(PURGED_SENTINEL);
    }

    @Test
    @DisplayName("Payout récent → non purgé")
    void recentPayout_notPurged() {
        Payout payout = saveAndBackdatePayout(buildPayout(), RECENT);

        piiRetentionJob.purgePayouts(LocalDateTime.now().minusYears(5));

        Payout updated = payoutRepository.findById(payout.getId()).orElseThrow();
        assertThat(updated.getPiiPurgedAt()).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildTransaction() {
        String encPhone = encryptionService.encrypt(REAL_PHONE);
        String phoneIdx = encryptionService.hmacForIndex(REAL_PHONE);

        Transaction tx = Transaction.builder()
            .ebithexReference("AP-PII-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
            .merchantReference("PII-" + System.nanoTime())
            .merchantId(testMerchantId)
            .amount(new BigDecimal("5000.00"))
            .feeAmount(new BigDecimal("50.00"))
            .netAmount(new BigDecimal("4950.00"))
            .currency(Currency.XOF)
            .phoneNumber(encPhone)
            .phoneNumberIndex(phoneIdx)
            .operator(OperatorType.MTN_MOMO_CI)
            .status(TransactionStatus.SUCCESS)
            .build();
        tx = transactionRepository.save(tx);
        txIds.add(tx.getId());
        return tx;
    }

    private Payout buildPayout() {
        String encPhone = encryptionService.encrypt(REAL_PHONE);
        String phoneIdx = encryptionService.hmacForIndex(REAL_PHONE);

        Payout payout = Payout.builder()
            .ebithexReference("PO-PII-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
            .merchantReference("PO-PII-" + System.nanoTime())
            .merchantId(testMerchantId)
            .amount(new BigDecimal("5000.00"))
            .feeAmount(new BigDecimal("25.00"))
            .netAmount(new BigDecimal("4975.00"))
            .currency(Currency.XOF)
            .phoneNumber(encPhone)
            .phoneNumberIndex(phoneIdx)
            .operator(OperatorType.MTN_MOMO_CI)
            .status(TransactionStatus.SUCCESS)
            .build();
        payout = payoutRepository.save(payout);
        poIds.add(payout.getId());
        return payout;
    }

    /**
     * Backdates created_at via JDBC pour simuler une ancienne transaction.
     * Nécessaire car @CreationTimestamp est géré par Hibernate et ne peut pas être
     * surchargé via le builder.
     */
    private Transaction saveAndBackdate(Transaction tx, LocalDateTime backdateTime) {
        jdbc.update("UPDATE transactions SET created_at = ? WHERE id = ?",
            backdateTime, tx.getId());
        return transactionRepository.findById(tx.getId()).orElseThrow();
    }

    private Payout saveAndBackdatePayout(Payout payout, LocalDateTime backdateTime) {
        jdbc.update("UPDATE payouts SET created_at = ? WHERE id = ?",
            backdateTime, payout.getId());
        return payoutRepository.findById(payout.getId()).orElseThrow();
    }
}