package com.ebithex.payment.dto;

import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.domain.OperatorType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder
public class TransactionStatusResponse {
    private UUID transactionId;
    private String ebithexReference;
    private String merchantReference;
    private UUID merchantId;
    private TransactionStatus status;
    private BigDecimal amount;
    private String currency;
    private String phoneNumber;
    private OperatorType operator;
    private String operatorReference;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
