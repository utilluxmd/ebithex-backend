package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration — Verrouillage optimiste sur Transaction.
 *
 * Vérifie que le champ {@code @Version} sur {@link com.ebithex.payment.domain.Transaction}
 * prévient les mises à jour silencieuses en cas de concurrence (ex : callback opérateur
 * et job d'expiration modifiant le statut simultanément).
 *
 * Protocole :
 *  1. Insérer une transaction en base via JDBC (version = 0)
 *  2. Charger l'entité via JPA (entité détachée, version = 0 en mémoire)
 *  3. Simuler une mise à jour concurrente via JDBC (version passe à 1 en base)
 *  4. Tenter de sauvegarder l'entité périmée → {@link ObjectOptimisticLockingFailureException}
 */
@DisplayName("Transaction — Verrouillage optimiste (@Version)")
class TransactionOptimisticLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = factory.registerKycVerifiedMerchant().merchantId();
    }

    @Test
    @DisplayName("Mise à jour concurrente détectée — OptimisticLockingFailureException levée")
    void concurrentStatusUpdate_staleVersion_throwsOptimisticLockException() {
        UUID txId     = UUID.randomUUID();
        UUID merchant = this.merchantId;

        // 1. Insérer une transaction minimale directement en DB (version = 0)
        jdbcTemplate.update("""
            INSERT INTO transactions
                (id, ebithex_reference, merchant_reference, merchant_id,
                 amount, fee_amount, net_amount, currency,
                 phone_number, operator, status, version)
            VALUES
                (?, ?, ?, ?,
                 ?, ?, ?, ?,
                 ?, ?, ?, 0)
            """,
            txId,
            "AP-OPT-" + txId.toString().substring(0, 8),
            "REF-OPT-001",
            merchant,
            new BigDecimal("5000.00"),
            new BigDecimal("75.00"),
            new BigDecimal("4925.00"),
            "XOF",
            "encrypted:placeholder",
            "MTN_MOMO_CI",
            "PENDING"
        );

        // 2. Charger l'entité via JPA dans une transaction courte → entité détachée (version = 0)
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        var staleEntity = txTemplate.execute(
            s -> transactionRepository.findById(txId).orElseThrow());

        assertThat(staleEntity).isNotNull();
        assertThat(staleEntity.getVersion()).isEqualTo(0L);

        // 3. Simuler une mise à jour concurrente en contournant JPA (version → 1 en base)
        int updated = jdbcTemplate.update(
            "UPDATE transactions SET version = 1, status = 'SUCCESS' WHERE id = ?", txId);
        assertThat(updated).isEqualTo(1);

        // 4. Tenter de sauvegarder l'entité périmée dans une nouvelle transaction
        //    → Hibernate détecte que version DB (1) ≠ version entité (0) → exception
        staleEntity.setStatus(TransactionStatus.FAILED);

        assertThatThrownBy(() ->
            txTemplate.execute(s -> {
                transactionRepository.saveAndFlush(staleEntity);
                return null;
            })
        )
        .as("Une entité périmée doit lever ObjectOptimisticLockingFailureException")
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 5. Vérifier que la base conserve la version gagnante (success du thread concurrent)
        String finalStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM transactions WHERE id = ?", String.class, txId);
        assertThat(finalStatus).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Mise à jour séquentielle → version incrémentée normalement")
    void sequentialUpdates_versionIncrements() {
        UUID txId     = UUID.randomUUID();
        UUID merchant = this.merchantId;

        jdbcTemplate.update("""
            INSERT INTO transactions
                (id, ebithex_reference, merchant_reference, merchant_id,
                 amount, fee_amount, net_amount, currency,
                 phone_number, operator, status, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """,
            txId, "AP-SEQ-" + txId.toString().substring(0, 8), "REF-SEQ-001",
            merchant, new BigDecimal("3000.00"), new BigDecimal("45.00"),
            new BigDecimal("2955.00"), "XOF", "encrypted:placeholder", "ORANGE_MONEY_CI", "PENDING"
        );

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Première mise à jour
        txTemplate.execute(s -> {
            var tx = transactionRepository.findById(txId).orElseThrow();
            tx.setStatus(TransactionStatus.SUCCESS);
            return transactionRepository.saveAndFlush(tx);
        });

        Long versionAfterFirstUpdate = jdbcTemplate.queryForObject(
            "SELECT version FROM transactions WHERE id = ?", Long.class, txId);
        assertThat(versionAfterFirstUpdate).isEqualTo(1L);

        // Deuxième mise à jour (idempotente sur le statut) — version doit passer à 2
        txTemplate.execute(s -> {
            var tx = transactionRepository.findById(txId).orElseThrow();
            tx.setOperatorReference("OP-REF-FINAL");
            return transactionRepository.saveAndFlush(tx);
        });

        Long versionAfterSecondUpdate = jdbcTemplate.queryForObject(
            "SELECT version FROM transactions WHERE id = ?", Long.class, txId);
        assertThat(versionAfterSecondUpdate).isEqualTo(2L);
    }
}
