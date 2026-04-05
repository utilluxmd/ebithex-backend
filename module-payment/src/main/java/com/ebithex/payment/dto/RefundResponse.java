package com.ebithex.payment.dto;

import com.ebithex.shared.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Réponse à un remboursement initié via POST /v1/payments/{ref}/refund.
 */
public record RefundResponse(
    UUID          transactionId,
    String        ebithexReference,
    String        merchantReference,
    BigDecimal    amount,
    BigDecimal    refundedAmount,
    BigDecimal    remainingAmount,
    String        currency,
    TransactionStatus status,
    LocalDateTime refundedAt
) {}