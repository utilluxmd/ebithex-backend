package com.ebithex.wallet.dto;

import com.ebithex.shared.domain.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class WithdrawalRequest {

    @NotNull
    @DecimalMin(value = "100", message = "Le montant minimum de retrait est 100 unités")
    private BigDecimal amount;

    /** Devise du wallet à débiter. Défaut XOF si non précisé. */
    private Currency currency = Currency.XOF;

    private String description;
}