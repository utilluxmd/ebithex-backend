package com.ebithex.wallet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WithdrawalResponse(
    UUID          id,
    String        withdrawalReference,
    UUID          merchantId,
    BigDecimal    amount,
    String        currency,
    String        status,
    LocalDateTime createdAt
) {}