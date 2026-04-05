package com.ebithex.aml.dto;

import com.ebithex.aml.domain.AmlAlert;
import com.ebithex.aml.domain.AmlSeverity;
import com.ebithex.aml.domain.AmlStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AmlAlertResponse(
    UUID          id,
    UUID          merchantId,
    UUID          transactionId,
    String        ruleCode,
    AmlSeverity   severity,
    AmlStatus     status,
    String        details,
    BigDecimal    amount,
    String        currency,
    LocalDateTime createdAt,
    LocalDateTime reviewedAt,
    String        reviewedBy,
    String        resolutionNote
) {
    public static AmlAlertResponse from(AmlAlert a) {
        return new AmlAlertResponse(
            a.getId(), a.getMerchantId(), a.getTransactionId(),
            a.getRuleCode(), a.getSeverity(), a.getStatus(),
            a.getDetails(), a.getAmount(), a.getCurrency(),
            a.getCreatedAt(), a.getReviewedAt(), a.getReviewedBy(), a.getResolutionNote()
        );
    }
}