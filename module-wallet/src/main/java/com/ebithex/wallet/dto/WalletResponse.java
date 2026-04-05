package com.ebithex.wallet.dto;

import com.ebithex.wallet.domain.Wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WalletResponse(
    UUID        merchantId,
    BigDecimal  availableBalance,
    BigDecimal  pendingBalance,
    BigDecimal  totalBalance,
    String      currency,
    LocalDateTime updatedAt
) {
    public static WalletResponse from(Wallet w) {
        return new WalletResponse(
            w.getMerchantId(),
            w.getAvailableBalance(),
            w.getPendingBalance(),
            w.getAvailableBalance().add(w.getPendingBalance()),
            w.getCurrency().name(),
            w.getUpdatedAt()
        );
    }
}