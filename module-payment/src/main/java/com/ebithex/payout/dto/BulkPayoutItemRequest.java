package com.ebithex.payout.dto;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BulkPayoutItemRequest {

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "100.0", message = "Montant minimum : 100 FCFA")
    @DecimalMax(value = "2000000.0", message = "Montant maximum : 2 000 000 FCFA")
    private BigDecimal amount;

    private Currency currency = Currency.XOF;

    @NotBlank(message = "Le numéro bénéficiaire est obligatoire")
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Format international requis: +22507XXXXXXXX")
    private String phoneNumber;

    private OperatorType operator = OperatorType.AUTO;

    @NotBlank(message = "La référence item est obligatoire")
    @Size(max = 100)
    private String merchantReference;

    @Size(max = 255)
    private String description;

    private String beneficiaryName;
}