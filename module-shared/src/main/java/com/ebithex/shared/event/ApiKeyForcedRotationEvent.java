package com.ebithex.shared.event;

import java.util.UUID;

/**
 * Événement publié quand une clé API est désactivée automatiquement
 * pour dépassement de sa politique de rotation forcée.
 * Consommé par NotificationService pour envoyer un email d'alerte urgente au marchand.
 */
public record ApiKeyForcedRotationEvent(
    String  merchantEmail,
    String  businessName,
    UUID    keyId,
    String  keyLabel,
    String  keyHint,
    int     rotationRequiredDays
) {}
