package com.ebithex.operator.outbound;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;

import java.math.BigDecimal;

public record OperatorPaymentRequest(
        String ebithexReference,
        String phoneNumber,
        BigDecimal amount,
        Currency currency,
        OperatorType operator,
        String description,
        String callbackUrl
) {}
