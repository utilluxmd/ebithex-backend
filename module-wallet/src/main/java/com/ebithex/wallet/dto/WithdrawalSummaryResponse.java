package com.ebithex.wallet.dto;

import com.ebithex.wallet.domain.MerchantWithdrawal;
import com.ebithex.wallet.domain.WithdrawalStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WithdrawalSummaryResponse(
    UUID             id,
    UUID             merchantId,
    String           reference,
    BigDecimal       amount,
    String           currency,
    WithdrawalStatus status,
    String           description,
    String           rejectionReason,
    LocalDateTime    reviewedAt,
    LocalDateTime    createdAt
) {
    public static WithdrawalSummaryResponse from(MerchantWithdrawal w) {
        return new WithdrawalSummaryResponse(
            w.getId(), w.getMerchantId(), w.getReference(),
            w.getAmount(), w.getCurrency().name(),
            w.getStatus(), w.getDescription(), w.getRejectionReason(),
            w.getReviewedAt(), w.getCreatedAt()
        );
    }
}