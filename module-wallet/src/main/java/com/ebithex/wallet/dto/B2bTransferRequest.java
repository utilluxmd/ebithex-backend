package com.ebithex.wallet.dto;

import com.ebithex.shared.domain.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Requête de virement B2B inter-marchands Ebithex.
 *
 * @param receiverMerchantId UUID du marchand destinataire
 * @param amount             Montant à transférer (minimum 1 dans la devise)
 * @param currency           Devise du transfert (défaut XOF)
 * @param merchantReference  Référence unique fournie par le marchand (idempotence)
 * @param description        Description libre du virement (optionnelle)
 */
public record B2bTransferRequest(

    @NotNull(message = "Le marchand destinataire est obligatoire")
    UUID receiverMerchantId,

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "1.0", message = "Le montant minimum est 1")
    BigDecimal amount,

    Currency currency,

    @NotBlank(message = "La référence marchand est obligatoire")
    @Size(max = 100)
    String merchantReference,

    @Size(max = 255)
    String description
) {
    public B2bTransferRequest {
        if (currency == null) currency = Currency.XOF;
    }
}