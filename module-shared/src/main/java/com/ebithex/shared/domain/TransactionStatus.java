package com.ebithex.shared.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TransactionStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    EXPIRED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    CANCELLED;

    private static final java.util.Map<TransactionStatus, Set<TransactionStatus>> ALLOWED =
        new java.util.EnumMap<>(TransactionStatus.class);

    static {
        ALLOWED.put(PENDING,            EnumSet.of(PROCESSING, EXPIRED, CANCELLED));
        ALLOWED.put(PROCESSING,         EnumSet.of(SUCCESS, FAILED, EXPIRED, CANCELLED));
        ALLOWED.put(SUCCESS,            EnumSet.of(REFUNDED, PARTIALLY_REFUNDED));
        ALLOWED.put(PARTIALLY_REFUNDED, EnumSet.of(REFUNDED));
        ALLOWED.put(FAILED,             EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(EXPIRED,            EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(REFUNDED,           EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(CANCELLED,          EnumSet.noneOf(TransactionStatus.class));
    }

    public boolean canTransitionTo(TransactionStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(TransactionStatus.class)).contains(next);
    }
}
