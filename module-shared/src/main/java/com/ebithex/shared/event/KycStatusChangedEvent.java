package com.ebithex.shared.event;

import java.util.UUID;

/**
 * Published when a merchant's KYC dossier is approved or rejected.
 *
 * Consumers:
 *  - NotificationService : sends confirmation/rejection email to merchant
 *  - WebhookService      : notifies merchant's configured webhook URL
 */
public record KycStatusChangedEvent(
    UUID   merchantId,
    String merchantEmail,
    String businessName,
    String newStatus,      // "APPROVED" | "REJECTED"
    String rejectionReason // null when APPROVED
) {}