package com.ebithex.payout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PayoutResponse {

    private UUID payoutId;
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
    private String message;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
