package com.ebithex.operator.outbound;

import com.ebithex.shared.domain.TransactionStatus;

public record OperatorStatusResult(
        String operatorReference,
        TransactionStatus mappedStatus,
        String rawStatus
) {}
