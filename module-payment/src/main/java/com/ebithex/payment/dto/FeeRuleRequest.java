package com.ebithex.payment.dto;

import com.ebithex.payment.domain.FeeRule;
import com.ebithex.shared.domain.OperatorType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record FeeRuleRequest(
    @NotBlank String name,
    String     description,

    // Ciblage
    UUID         merchantId,
    OperatorType operator,
    String       country,

    // Structure
    @NotNull FeeRule.FeeType feeType,
    @DecimalMin("0.0") BigDecimal percentageRate,
    @DecimalMin("0.0") BigDecimal flatAmount,
    @DecimalMin("0.0") BigDecimal minFee,
    @DecimalMin("0.0") BigDecimal maxFee,

    // Applicabilité
    @DecimalMin("0.0") BigDecimal minAmount,
    @DecimalMin("0.0") BigDecimal maxAmount,

    // Priorité et validité
    Integer       priority,
    Boolean       active,
    LocalDateTime validFrom,
    LocalDateTime validUntil
) {}