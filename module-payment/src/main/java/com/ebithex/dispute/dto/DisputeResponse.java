package com.ebithex.dispute.dto;

import com.ebithex.dispute.domain.Dispute;
import com.ebithex.dispute.domain.DisputeReason;
import com.ebithex.dispute.domain.DisputeStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record DisputeResponse(
    UUID          id,
    String        ebithexReference,
    UUID          merchantId,
    UUID          transactionId,
    DisputeReason reason,
    String        description,
    BigDecimal    amount,
    String        currency,
    DisputeStatus status,
    LocalDateTime openedAt,
    LocalDateTime resolvedAt,
    String        resolvedBy,
    String        resolutionNotes
) {
    public static DisputeResponse from(Dispute d) {
        return new DisputeResponse(
            d.getId(), d.getEbithexReference(), d.getMerchantId(), d.getTransactionId(),
            d.getReason(), d.getDescription(), d.getAmount(), d.getCurrency(),
            d.getStatus(), d.getOpenedAt(), d.getResolvedAt(), d.getResolvedBy(), d.getResolutionNotes()
        );
    }
}