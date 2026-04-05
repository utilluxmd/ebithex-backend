package com.ebithex.payment.dto;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentRequest {

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "100.0", message = "Montant minimum : 100 FCFA")
    @DecimalMax(value = "2000000.0", message = "Montant maximum : 2 000 000 FCFA")
    private BigDecimal amount;

    private Currency currency = Currency.XOF;

    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Format international requis: +22507XXXXXXXX")
    private String phoneNumber;

    private OperatorType operator = OperatorType.AUTO;

    @NotBlank(message = "La référence marchand est obligatoire")
    @Size(max = 100)
    private String merchantReference;

    @Size(max = 255)
    @Pattern(regexp = "^[^<>\"'\\x00-\\x1F]*$", message = "La description ne doit pas contenir de caractères HTML ni de caractères de contrôle")
    private String description;

    @Size(max = 100)
    @Pattern(regexp = "^[^<>\"'\\x00-\\x1F]*$", message = "Le nom ne doit pas contenir de caractères HTML ni de caractères de contrôle")
    private String customerName;

    @Pattern(regexp = "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$",
             message = "Format email invalide",
             flags = Pattern.Flag.CASE_INSENSITIVE)
    @Size(max = 255)
    private String customerEmail;

    @Size(max = 1000, message = "Les métadonnées ne doivent pas dépasser 1000 caractères")
    @Pattern(regexp = "^[^<>\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]*$", message = "Les métadonnées ne doivent pas contenir de balises HTML ni de caractères de contrôle")
    private String metadata;

    @Size(max = 500)
    @Pattern(regexp = "^(https?://.*)?$", message = "returnUrl doit être une URL http/https valide")
    private String returnUrl;

    @Size(max = 500)
    @Pattern(regexp = "^(https?://.*)?$", message = "cancelUrl doit être une URL http/https valide")
    private String cancelUrl;
}
