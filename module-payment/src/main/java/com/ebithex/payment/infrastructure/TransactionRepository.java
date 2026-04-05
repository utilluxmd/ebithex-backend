package com.ebithex.payment.infrastructure;

import com.ebithex.payment.domain.Transaction;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByEbithexReference(String reference);
    Optional<Transaction> findByMerchantReferenceAndMerchantId(String merchantReference, UUID merchantId);
    Optional<Transaction> findByOperatorReference(String operatorReference);
    Page<Transaction> findByMerchantId(UUID merchantId, Pageable pageable);

    /** Recherche par HMAC du numéro de téléphone (numéro jamais stocké en clair). */
    Page<Transaction> findByMerchantIdAndPhoneNumberIndex(UUID merchantId,
                                                           String phoneNumberIndex,
                                                           Pageable pageable);

    /**
     * Vérifie si un numéro de téléphone a déjà effectué au moins une transaction
     * chez un marchand donné. Utilise l'index HMAC pour ne jamais manipuler le numéro en clair.
     */
    boolean existsByMerchantIdAndPhoneNumberIndex(UUID merchantId, String phoneNumberIndex);

    /** Transactions dont la date d'expiration est dépassée et dont le statut est encore terminal. */
    @Query("SELECT t FROM Transaction t WHERE t.status IN :statuses AND t.expiresAt IS NOT NULL AND t.expiresAt < :now")
    List<Transaction> findExpiredPending(
        @Param("statuses") List<TransactionStatus> statuses,
        @Param("now")      LocalDateTime now);

    /** Requête de réconciliation avec filtres optionnels sur country, merchantId, status, opérateur et plage de dates.
     *  country est non-null uniquement pour COUNTRY_ADMIN — filtre via sous-requête sur Merchant.country. */
    @Query("SELECT t FROM Transaction t WHERE " +
        "(:country IS NULL OR t.merchantId IN " +
            "(SELECT m.id FROM com.ebithex.merchant.domain.Merchant m WHERE m.country = :country)) AND " +
        "(:merchantId IS NULL OR t.merchantId = :merchantId) AND " +
        "(:status IS NULL OR t.status = :status) AND " +
        "(:operator IS NULL OR t.operator = :operator) AND " +
        "t.createdAt BETWEEN :from AND :to")
    Page<Transaction> findForReconciliation(
        @Param("country")    String country,
        @Param("merchantId") UUID merchantId,
        @Param("status")     TransactionStatus status,
        @Param("operator")   OperatorType operator,
        @Param("from")       LocalDateTime from,
        @Param("to")         LocalDateTime to,
        Pageable pageable);

    /**
     * Somme des montants des transactions actives d'un marchand depuis une date donnée.
     * Utilisé pour la vérification des plafonds journaliers/mensuels.
     * Le schéma correct (public ou sandbox) est sélectionné via search_path.
     */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM transactions " +
        "WHERE merchant_id = :merchantId AND " +
        "status IN ('PENDING','PROCESSING','SUCCESS') AND " +
        "(created_at IS NULL OR created_at >= :from)",
        nativeQuery = true)
    BigDecimal sumActiveAmountSince(
        @Param("merchantId") UUID merchantId,
        @Param("from")       LocalDateTime from);

    /** Nombre de transactions d'un marchand depuis une date donnée (velocity AML). */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.merchantId = :merchantId AND t.createdAt > :since")
    long countByMerchantIdAndCreatedAtAfter(
        @Param("merchantId") UUID merchantId,
        @Param("since")      LocalDateTime since);

    /** Détection de structuring : transactions dont le montant est dans ]lowerBound, upperBound[ en 24h. */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.merchantId = :merchantId AND t.amount > :lower AND t.amount < :upper AND t.createdAt > :since")
    long countStructuringAttempts(
        @Param("merchantId") UUID merchantId,
        @Param("lower")      BigDecimal lower,
        @Param("upper")      BigDecimal upper,
        @Param("since")      LocalDateTime since);

    /** Transactions SUCCESS d'un opérateur sur une période — pour le calcul de settlement. */
    @Query("SELECT t FROM Transaction t WHERE t.operator = :operator AND t.status = com.ebithex.shared.domain.TransactionStatus.SUCCESS AND t.createdAt BETWEEN :from AND :to")
    List<Transaction> findSuccessForSettlement(
        @Param("operator") OperatorType operator,
        @Param("from")     LocalDateTime from,
        @Param("to")       LocalDateTime to);

    /**
     * Transactions éligibles à la purge PII : créées avant le seuil et pas encore purgées.
     * Le schéma (public ou sandbox) est sélectionné via search_path du pool actif.
     */
    @Query("SELECT t FROM Transaction t WHERE t.createdAt < :cutoff AND t.piiPurgedAt IS NULL")
    Page<Transaction> findPurgeCandidates(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * Transactions SUCCESS/PARTIELLEMENT_REMBOURSÉES/REMBOURSÉES d'un opérateur sur une journée.
     * Utilisé pour la réconciliation MISSING_IN_OPERATOR : transactions Ebithex absentes du relevé opérateur.
     */
    @Query("SELECT t FROM Transaction t WHERE t.operator = :operator AND t.createdAt >= :from AND t.createdAt < :to AND t.status IN :statuses AND t.operatorReference IS NOT NULL")
    List<Transaction> findByOperatorAndDateAndStatusIn(
        @Param("operator") OperatorType operator,
        @Param("from")     LocalDateTime from,
        @Param("to")       LocalDateTime to,
        @Param("statuses") List<TransactionStatus> statuses);

    /**
     * Transactions dont le champ phone_number_encrypted n'est pas encore chiffré
     * avec la version de clé active (migration lors d'une rotation de clé).
     * Le préfixe de version (:versionPrefix = "v{N}:") est passé en paramètre.
     * Les ciphertexts hérités (sans préfixe) et ceux d'anciennes versions sont retournés.
     */
    @Query(value = "SELECT * FROM transactions " +
        "WHERE phone_number IS NOT NULL " +
        "AND pii_purged_at IS NULL " +
        "AND phone_number NOT LIKE :versionPrefix",
        countQuery = "SELECT COUNT(*) FROM transactions " +
        "WHERE phone_number IS NOT NULL " +
        "AND pii_purged_at IS NULL " +
        "AND phone_number NOT LIKE :versionPrefix",
        nativeQuery = true)
    Page<Transaction> findNeedingReEncryption(
        @Param("versionPrefix") String versionPrefix,
        Pageable pageable);

    /** Agrégats pour le summary de réconciliation : [status, count, sumAmount, sumFeeAmount]. */
    @Query("SELECT t.status, COUNT(t), SUM(t.amount), SUM(t.feeAmount) FROM Transaction t WHERE " +
        "(:country IS NULL OR t.merchantId IN " +
            "(SELECT m.id FROM com.ebithex.merchant.domain.Merchant m WHERE m.country = :country)) AND " +
        "t.createdAt BETWEEN :from AND :to " +
        "GROUP BY t.status")
    List<Object[]> aggregateByStatus(
        @Param("country") String country,
        @Param("from")    LocalDateTime from,
        @Param("to")      LocalDateTime to);
}
