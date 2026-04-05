package com.ebithex.settlement.infrastructure;

import com.ebithex.settlement.domain.SettlementBatch;
import com.ebithex.settlement.domain.SettlementBatchStatus;
import com.ebithex.shared.domain.OperatorType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {

    Optional<SettlementBatch> findByBatchReference(String batchReference);

    Page<SettlementBatch> findByOperatorAndStatus(OperatorType operator, SettlementBatchStatus status, Pageable pageable);

    @Query("SELECT b FROM SettlementBatch b WHERE " +
        "(:operator IS NULL OR b.operator = :operator) AND " +
        "(:status   IS NULL OR b.status   = :status) AND " +
        "b.periodStart >= :from AND b.periodEnd <= :to")
    Page<SettlementBatch> findForReport(
        @Param("operator") OperatorType operator,
        @Param("status")   SettlementBatchStatus status,
        @Param("from")     LocalDateTime from,
        @Param("to")       LocalDateTime to,
        Pageable pageable);

    boolean existsByOperatorAndCurrencyAndPeriodStart(OperatorType operator, String currency, LocalDateTime periodStart);
}
