package com.ebithex.payout.infrastructure;

import com.ebithex.payout.domain.Payout;
import com.ebithex.shared.domain.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, UUID> {
    Optional<Payout> findByEbithexReference(String reference);
    Optional<Payout> findByMerchantReferenceAndMerchantId(String merchantReference, UUID merchantId);
    Optional<Payout> findByOperatorReference(String operatorReference);
    Page<Payout> findByMerchantId(UUID merchantId, Pageable pageable);

    /** Payouts dont la date d'expiration est dépassée et dont le statut est encore terminal. */
    @Query("SELECT p FROM Payout p WHERE p.status IN :statuses AND p.expiresAt IS NOT NULL AND p.expiresAt < :now")
    List<Payout> findExpiredPending(
        @Param("statuses") List<TransactionStatus> statuses,
        @Param("now")      LocalDateTime now);

    @Query("SELECT p FROM Payout p WHERE " +
        "(:country IS NULL OR p.merchantId IN " +
            "(SELECT m.id FROM com.ebithex.merchant.domain.Merchant m WHERE m.country = :country)) AND " +
        "(:merchantId IS NULL OR p.merchantId = :merchantId) AND " +
        "(:status IS NULL OR p.status = :status) AND " +
        "p.createdAt BETWEEN :from AND :to")
    Page<Payout> findForReconciliation(
        @Param("country")    String country,
        @Param("merchantId") UUID merchantId,
        @Param("status")     TransactionStatus status,
        @Param("from")       LocalDateTime from,
        @Param("to")         LocalDateTime to,
        Pageable pageable);

    /** Payouts éligibles à la purge PII : créés avant le seuil et pas encore purgés. */
    @Query("SELECT p FROM Payout p WHERE p.createdAt < :cutoff AND p.piiPurgedAt IS NULL")
    Page<Payout> findPurgeCandidates(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * Payouts dont le champ phone_number_encrypted n'est pas encore chiffré
     * avec la version de clé active (migration lors d'une rotation de clé).
     */
    @Query(value = "SELECT * FROM payouts " +
        "WHERE phone_number IS NOT NULL " +
        "AND pii_purged_at IS NULL " +
        "AND phone_number NOT LIKE :versionPrefix",
        countQuery = "SELECT COUNT(*) FROM payouts " +
        "WHERE phone_number IS NOT NULL " +
        "AND pii_purged_at IS NULL " +
        "AND phone_number NOT LIKE :versionPrefix",
        nativeQuery = true)
    Page<Payout> findNeedingReEncryption(
        @Param("versionPrefix") String versionPrefix,
        Pageable pageable);

    @Query("SELECT p.status, COUNT(p), SUM(p.amount), SUM(p.feeAmount) FROM Payout p WHERE " +
        "(:country IS NULL OR p.merchantId IN " +
            "(SELECT m.id FROM com.ebithex.merchant.domain.Merchant m WHERE m.country = :country)) AND " +
        "p.createdAt BETWEEN :from AND :to " +
        "GROUP BY p.status")
    List<Object[]> aggregateByStatus(
        @Param("country") String country,
        @Param("from")    LocalDateTime from,
        @Param("to")      LocalDateTime to);
}
