package com.ebithex.shared.event;

import java.util.UUID;

/**
 * Événement publié quand une clé API dépasse le seuil de vieillissement.
 * Consommé par NotificationService pour envoyer un email de rappel au marchand.
 */
public record ApiKeyAgingReminderEvent(
    String  merchantEmail,
    String  businessName,
    UUID    keyId,
    String  keyLabel,
    String  keyHint,
    long    keyAgeDays,
    int     alertThresholdDays
) {}
