package com.ebithex.operator.outbound;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;

import java.math.BigDecimal;

/**
 * Request sent to a Mobile Money operator to initiate a disbursement (payout).
 * Mirror of OperatorPaymentRequest but with "payee" semantics.
 */
public record OperatorDisbursementRequest(
        String ebithexReference,
        String phoneNumber,   // numéro bénéficiaire normalisé E.164
        BigDecimal amount,
        Currency currency,
        OperatorType operator,
        String description,
        String callbackUrl
) {}
