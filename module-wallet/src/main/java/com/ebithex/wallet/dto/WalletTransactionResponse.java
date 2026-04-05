package com.ebithex.wallet.dto;

import com.ebithex.wallet.domain.WalletTransaction;
import com.ebithex.wallet.domain.WalletTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WalletTransactionResponse(
    UUID                   id,
    WalletTransactionType  type,
    BigDecimal             amount,
    BigDecimal             balanceAfter,
    String                 ebithexReference,
    String                 description,
    LocalDateTime          createdAt
) {
    public static WalletTransactionResponse from(WalletTransaction t) {
        return new WalletTransactionResponse(
            t.getId(),
            t.getType(),
            t.getAmount(),
            t.getBalanceAfter(),
            t.getEbithexReference(),
            t.getDescription(),
            t.getCreatedAt()
        );
    }
}