package com.ebithex.webhook.api;

import com.ebithex.shared.api.ApiResponse;
import com.ebithex.webhook.application.WebhookService;
import com.ebithex.webhook.domain.WebhookDelivery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints back-office pour la Dead Letter Queue des webhooks.
 *
 * GET  /internal/webhooks/dead-letters         — lister les livraisons en DLQ
 * POST /internal/webhooks/dead-letters/{id}/retry — relancer une livraison
 */
@RestController
@RequestMapping("/internal/webhooks")
@RequiredArgsConstructor
@Tag(name = "Back-office — Webhooks DLQ")
@SecurityRequirement(name = "BearerAuth")
public class WebhookAdminController {

    private final WebhookService webhookService;

    @GetMapping("/dead-letters")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','SUPPORT')")
    @Operation(summary = "Lister les webhooks en Dead Letter Queue (5 tentatives épuisées)")
    public ResponseEntity<ApiResponse<List<WebhookDelivery>>> listDeadLetters() {
        return ResponseEntity.ok(ApiResponse.ok(webhookService.listDeadLetters()));
    }

    @PostMapping("/dead-letters/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Relancer manuellement une livraison en DLQ")
    public ResponseEntity<ApiResponse<Void>> retryDeadLetter(@PathVariable UUID id) {
        webhookService.retryDeadLetter(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}