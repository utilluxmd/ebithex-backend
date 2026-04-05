package com.ebithex.webhook.api;

import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.webhook.application.WebhookService;
import com.ebithex.webhook.domain.WebhookDelivery;
import com.ebithex.webhook.dto.WebhookRequest;
import com.ebithex.webhook.dto.WebhookResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
@RequiredArgsConstructor
@Validated
@Tag(name = "Webhooks", description = "Gestion des endpoints webhook marchands")
@SecurityRequirement(name = "ApiKey")
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).FULL_ACCESS)")
    @Operation(summary = "Enregistrer un endpoint webhook")
    public ResponseEntity<ApiResponse<WebhookResponse>> register(
            @Valid @RequestBody WebhookRequest request,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        WebhookResponse response = webhookService.register(principal.merchantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .header("Location", "/api/v1/webhooks/" + response.id())
            .body(ApiResponse.ok("Webhook enregistré", response));
    }

    @GetMapping
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).WEBHOOKS_READ)")
    @Operation(summary = "Lister les endpoints webhook")
    public ResponseEntity<ApiResponse<List<WebhookResponse>>> list(
            @AuthenticationPrincipal EbithexPrincipal principal) {

        List<WebhookResponse> endpoints = webhookService.listForMerchant(principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(endpoints));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).FULL_ACCESS)")
    @Operation(summary = "Désactiver un endpoint webhook")
    public ResponseEntity<ApiResponse<Void>> disable(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        webhookService.disable(id, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/{id}/deliveries")
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).WEBHOOKS_READ)")
    @Operation(
        summary = "Historique des livraisons",
        description = "Retourne l'historique paginé des tentatives de livraison pour un endpoint webhook. " +
                      "Inclut le statut HTTP, le nombre de tentatives, les erreurs et les dates de livraison/retry."
    )
    public ResponseEntity<ApiResponse<Page<WebhookDelivery>>> listDeliveries(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<WebhookDelivery> deliveries = webhookService.listDeliveries(id, principal.merchantId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(deliveries));
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).FULL_ACCESS)")
    @Operation(summary = "Envoyer un événement de test",
               description = "Envoie un événement synthétique `webhook.test` vers l'URL configurée. " +
                             "Permet de valider que l'URL est accessible et que la signature HMAC est correctement vérifiée. " +
                             "La livraison test n'est pas persistée dans l'historique des livraisons.")
    public ResponseEntity<ApiResponse<WebhookService.WebhookTestResult>> sendTest(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        WebhookService.WebhookTestResult result = webhookService.sendTestDelivery(id, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok("Test envoyé", result));
    }
}
