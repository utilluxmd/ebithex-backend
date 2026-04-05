package com.ebithex.payment.dto;

import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.domain.OperatorType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder
public class PaymentResponse {
    private UUID transactionId;
    private String ebithexReference;
    private String merchantReference;
    private TransactionStatus status;
    private BigDecimal amount;
    private BigDecimal feeAmount;
    private BigDecimal netAmount;
    private String currency;
    private String phoneNumber;
    private OperatorType operator;
    private String operatorReference;
    private String failureReason;
    private String message;
    private String paymentUrl;
    private String ussdCode;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    /** true si la réponse provient d'un replay idempotent (même merchantReference). */
    private boolean idempotentReplay;
}
