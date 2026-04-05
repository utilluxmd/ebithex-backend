package com.ebithex.merchant.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class MerchantRegistrationRequest {

    @NotBlank
    @Size(min = 2, max = 100)
    private String businessName;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8)
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&+\\-#]).{8,}$",
        message = "Le mot de passe doit contenir au moins une majuscule, une minuscule, un chiffre et un caractère spécial (@$!%*?&+-#)"
    )
    private String password;

    @NotBlank
    @Size(min = 2, max = 3)
    private String country;

    private String webhookUrl;
}
