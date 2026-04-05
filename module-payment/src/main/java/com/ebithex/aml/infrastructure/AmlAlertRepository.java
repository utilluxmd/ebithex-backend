package com.ebithex.aml.infrastructure;

import com.ebithex.aml.domain.AmlAlert;
import com.ebithex.aml.domain.AmlSeverity;
import com.ebithex.aml.domain.AmlStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AmlAlertRepository extends JpaRepository<AmlAlert, UUID> {

    Page<AmlAlert> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<AmlAlert> findByStatus(AmlStatus status, Pageable pageable);

    long countByMerchantIdAndCreatedAtAfter(UUID merchantId, LocalDateTime since);

    @Query("SELECT COUNT(a) FROM AmlAlert a WHERE a.merchantId = :merchantId AND a.ruleCode = :ruleCode AND a.createdAt > :since")
    long countByMerchantRuleAndPeriod(
        @Param("merchantId") UUID merchantId,
        @Param("ruleCode")   String ruleCode,
        @Param("since")      LocalDateTime since);

    @Query("SELECT a FROM AmlAlert a WHERE " +
        "(:status IS NULL OR a.status = :status) AND " +
        "(:merchantId IS NULL OR a.merchantId = :merchantId) AND " +
        "a.createdAt BETWEEN :from AND :to")
    Page<AmlAlert> findForReview(
        @Param("status")     AmlStatus status,
        @Param("merchantId") UUID merchantId,
        @Param("from")       LocalDateTime from,
        @Param("to")         LocalDateTime to,
        Pageable pageable);

    /**
     * Requête pour l'export SAR (Suspicious Activity Report).
     * Retourne les alertes HIGH et CRITICAL non encore déclarées aux autorités,
     * triées chronologiquement pour faciliter la numérotation du rapport.
     */
    @Query("SELECT a FROM AmlAlert a WHERE " +
        "a.severity IN :severities AND " +
        "a.status IN :statuses AND " +
        "a.createdAt BETWEEN :from AND :to " +
        "ORDER BY a.createdAt ASC")
    List<AmlAlert> findForSarExport(
        @Param("severities") List<AmlSeverity> severities,
        @Param("statuses")   List<AmlStatus>   statuses,
        @Param("from")       LocalDateTime     from,
        @Param("to")         LocalDateTime     to);
}