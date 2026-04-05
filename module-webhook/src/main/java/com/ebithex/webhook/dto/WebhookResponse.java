package com.ebithex.webhook.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record WebhookResponse(
        UUID id,
        String url,
        Set<String> events,
        boolean active,
        Instant createdAt,
        String signingSecret
) {}
