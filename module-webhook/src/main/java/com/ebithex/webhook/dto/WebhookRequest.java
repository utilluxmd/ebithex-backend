package com.ebithex.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.Set;

public record WebhookRequest(
        @NotBlank @URL @Size(max = 500) String url,
        Set<String> events
) {}
