package com.ebithex.settlement.dto;

import com.ebithex.settlement.domain.SettlementBatch;
import com.ebithex.settlement.domain.SettlementBatchStatus;
import com.ebithex.shared.domain.OperatorType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementBatchResponse(
    UUID                  id,
    String                batchReference,
    OperatorType          operator,
    String                currency,
    LocalDateTime         periodStart,
    LocalDateTime         periodEnd,
    int                   transactionCount,
    BigDecimal            grossAmount,
    BigDecimal            feeAmount,
    BigDecimal            netAmount,
    SettlementBatchStatus status,
    LocalDateTime         settledAt,
    LocalDateTime         createdAt
) {
    public static SettlementBatchResponse from(SettlementBatch b) {
        return new SettlementBatchResponse(
            b.getId(), b.getBatchReference(), b.getOperator(), b.getCurrency(),
            b.getPeriodStart(), b.getPeriodEnd(),
            b.getTransactionCount(), b.getGrossAmount(), b.getFeeAmount(), b.getNetAmount(),
            b.getStatus(), b.getSettledAt(), b.getCreatedAt()
        );
    }
}
