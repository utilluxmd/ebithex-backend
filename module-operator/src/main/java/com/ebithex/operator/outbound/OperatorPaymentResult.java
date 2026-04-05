package com.ebithex.operator.outbound;

public record OperatorPaymentResult(
        String operatorReference,
        String rawStatus,
        boolean accepted
) {}
