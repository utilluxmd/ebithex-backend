package com.ebithex.webhook.domain;

/**
 * Canonical event names sent in webhook payloads.
 */
public final class WebhookEvent {

    private WebhookEvent() {}

    public static final String PAYMENT_SUCCESS  = "payment.success";
    public static final String PAYMENT_FAILED   = "payment.failed";
    public static final String PAYMENT_EXPIRED  = "payment.expired";
    public static final String PAYMENT_PENDING  = "payment.pending";
    public static final String REFUND_COMPLETED         = "refund.completed";
    public static final String REFUND_PARTIAL_COMPLETED = "refund.partial_completed";
    public static final String PAYMENT_CANCELLED        = "payment.cancelled";

    public static final String PAYOUT_SUCCESS   = "payout.success";
    public static final String PAYOUT_FAILED    = "payout.failed";
    public static final String PAYOUT_EXPIRED   = "payout.expired";

    /** Événement synthétique — envoyé par POST /v1/webhooks/{id}/test pour valider la configuration. */
    public static final String WEBHOOK_TEST     = "webhook.test";
}
