package com.ebithex.shared.event;

/**
 * Événement déclenché après une connexion back-office réussie (mot de passe vérifié)
 * quand le 2FA est activé sur le compte opérateur.
 *
 * NotificationService écoute cet événement et envoie l'OTP par email.
 */
public record TwoFactorOtpEvent(
    String email,
    String otp
) {}