package com.ebithex.merchant.dto;

import com.ebithex.shared.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload de création d'un utilisateur back-office.
 *
 * @param email            Adresse email unique (identifiant de connexion)
 * @param role             Rôle attribué
 * @param country          Code pays ISO-3166-1 alpha-2 — obligatoire si role = COUNTRY_ADMIN
 * @param password         Mot de passe initial (min 10 car., lettre + chiffre + spécial)
 * @param twoFactorEnabled Activer la 2FA email OTP (défaut : true)
 */
public record StaffUserRequest(

    @NotBlank @Email
    String email,

    @NotNull
    Role role,

    String country,

    @NotBlank
    @Size(min = 10, message = "Le mot de passe doit contenir au moins 10 caractères")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{10,}$",
        message = "Le mot de passe doit contenir au moins une majuscule, une minuscule, un chiffre et un caractère spécial"
    )
    String password,

    boolean twoFactorEnabled
) {}