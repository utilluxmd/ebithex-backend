package com.ebithex.payout.dto;

import com.ebithex.payout.domain.Payout;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PayoutSummaryResponse(
    UUID              payoutId,
    String            ebithexReference,
    String            merchantReference,
    UUID              merchantId,
    TransactionStatus status,
    BigDecimal        amount,
    BigDecimal        feeAmount,
    BigDecimal        netAmount,
    String            currency,
    OperatorType      operator,
    String            operatorReference,
    String            failureReason,
    LocalDateTime     createdAt,
    LocalDateTime     updatedAt
) {
    public static PayoutSummaryResponse from(Payout p) {
        return new PayoutSummaryResponse(
            p.getId(), p.getEbithexReference(), p.getMerchantReference(), p.getMerchantId(),
            p.getStatus(), p.getAmount(), p.getFeeAmount(), p.getNetAmount(),
            p.getCurrency().name(), p.getOperator(), p.getOperatorReference(),
            p.getFailureReason(), p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}