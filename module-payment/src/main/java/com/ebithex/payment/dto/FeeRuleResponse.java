package com.ebithex.payment.dto;

import com.ebithex.payment.domain.FeeRule;
import com.ebithex.shared.domain.OperatorType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record FeeRuleResponse(
    UUID             id,
    String           name,
    String           description,
    UUID             merchantId,
    OperatorType     operator,
    String           country,
    FeeRule.FeeType  feeType,
    BigDecimal       percentageRate,
    BigDecimal       flatAmount,
    BigDecimal       minFee,
    BigDecimal       maxFee,
    BigDecimal       minAmount,
    BigDecimal       maxAmount,
    int              priority,
    boolean          active,
    LocalDateTime    validFrom,
    LocalDateTime    validUntil,
    LocalDateTime    createdAt
) {
    public static FeeRuleResponse from(FeeRule r) {
        return new FeeRuleResponse(
            r.getId(), r.getName(), r.getDescription(),
            r.getMerchantId(), r.getOperator(), r.getCountry(),
            r.getFeeType(), r.getPercentageRate(), r.getFlatAmount(),
            r.getMinFee(), r.getMaxFee(),
            r.getMinAmount(), r.getMaxAmount(),
            r.getPriority(), r.isActive(),
            r.getValidFrom(), r.getValidUntil(),
            r.getCreatedAt()
        );
    }
}