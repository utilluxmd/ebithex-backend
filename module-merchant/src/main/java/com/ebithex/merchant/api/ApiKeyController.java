package com.ebithex.merchant.api;

import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.dto.ApiKeyResponse;
import com.ebithex.merchant.dto.ApiKeyResponse.ApiKeyCreatedResponse;
import com.ebithex.merchant.dto.CreateApiKeyRequest;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.apikey.ApiKeyScope;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Endpoints de gestion des clés API — interface marchand.
 *
 * <p>Accessible via clé API ou JWT marchand.
 * Un marchand ne peut gérer que ses propres clés.
 */
@RestController
@RequestMapping("/v1/auth/api-keys")
@RequiredArgsConstructor
@Tag(name = "Clés API", description = "Listing, création, rotation et révocation des clés API — Rôles : MERCHANT · MERCHANT_KYC_VERIFIED")
@SecurityRequirement(name = "BearerAuth")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @GetMapping
    @Operation(summary = "Lister toutes les clés API du marchand")
    public ResponseEntity<ApiResponse<List<ApiKeyResponse>>> list(
            @AuthenticationPrincipal EbithexPrincipal principal) {
        List<ApiKeyResponse> keys = apiKeyService.listKeys(merchantId(principal))
            .stream().map(ApiKeyResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(keys));
    }

    @PostMapping
    @Operation(summary = "Créer une nouvelle clé API",
               description = "La valeur brute (`rawKey`) est retournée **une seule fois** — la stocker immédiatement. " +
                             "Sans scopes explicites, la clé reçoit FULL_ACCESS.")
    public ResponseEntity<ApiResponse<ApiKeyCreatedResponse>> create(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @Valid @RequestBody CreateApiKeyRequest req) {

        UUID mid = merchantId(principal);
        String rawKey = apiKeyService.createKey(
            mid, req.getType(), req.getLabel(),
            req.getScopes(), req.getAllowedIps(), req.getExpiresAt());

        // Charger la clé créée pour construire la réponse
        ApiKeyCreatedResponse created = apiKeyService.listKeys(mid).stream()
            .filter(k -> MessageDigest.isEqual(
                    ApiKeyService.sha256(rawKey).getBytes(StandardCharsets.UTF_8),
                    k.getKeyHash().getBytes(StandardCharsets.UTF_8)))
            .findFirst()
            .map(k -> ApiKeyCreatedResponse.from(k, rawKey, 0))
            .orElseThrow();

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PostMapping("/{keyId}/rotate")
    @Operation(summary = "Rotation d'une clé",
               description = "L'ancienne clé reste valide pendant la période de grâce (défaut 24 h). " +
                             "La nouvelle valeur brute est retournée une seule fois.")
    public ResponseEntity<ApiResponse<ApiKeyCreatedResponse>> rotate(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PathVariable UUID keyId) {

        UUID mid = merchantId(principal);
        String rawNew = apiKeyService.rotateKey(keyId, mid);

        ApiKeyCreatedResponse created = apiKeyService.listKeys(mid).stream()
            .filter(k -> ApiKeyService.sha256(rawNew).equals(k.getKeyHash()))
            .findFirst()
            .map(k -> ApiKeyCreatedResponse.from(k, rawNew, 24))
            .orElseThrow();

        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @DeleteMapping("/{keyId}")
    @Operation(summary = "Révoquer une clé API",
               description = "Révocation immédiate sans période de grâce. Ne pas révoquer la clé utilisée pour cette requête.")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PathVariable UUID keyId) {
        apiKeyService.revokeKey(keyId, merchantId(principal));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{keyId}/scopes")
    @Operation(summary = "Modifier les scopes d'une clé",
               description = "Modification immédiate. Scopes valides : FULL_ACCESS · PAYMENTS_WRITE · PAYMENTS_READ · PAYOUTS_WRITE · PAYOUTS_READ · WEBHOOKS_READ · PROFILE_READ")
    public ResponseEntity<ApiResponse<Void>> updateScopes(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PathVariable UUID keyId,
            @RequestBody Map<String, Set<ApiKeyScope>> body) {
        Set<ApiKeyScope> scopes = body.get("scopes");
        if (scopes == null || scopes.isEmpty()) {
            throw new EbithexException(ErrorCode.INVALID_SCOPES, "Le champ scopes est obligatoire");
        }
        apiKeyService.updateScopes(keyId, merchantId(principal), scopes);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/{keyId}/allowed-ips")
    @Operation(summary = "Modifier la liste blanche d'IPs",
               description = "Passer `allowedIps: null` ou liste vide pour supprimer toute restriction IP.")
    public ResponseEntity<ApiResponse<Void>> updateAllowedIps(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PathVariable UUID keyId,
            @RequestBody Map<String, Set<String>> body) {
        apiKeyService.updateAllowedIps(keyId, merchantId(principal), body.get("allowedIps"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/{keyId}/expires-at")
    @Operation(summary = "Définir ou supprimer la date d'expiration",
               description = "Passer `expiresAt: null` pour supprimer l'expiration.")
    public ResponseEntity<ApiResponse<Void>> updateExpiry(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PathVariable UUID keyId,
            @RequestBody Map<String, LocalDateTime> body) {
        apiKeyService.updateExpiry(keyId, merchantId(principal), body.get("expiresAt"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }


    // ── Helper ────────────────────────────────────────────────────────────────

    private UUID merchantId(EbithexPrincipal principal) {
        UUID mid = principal.merchantId();
        if (mid == null) {
            throw new EbithexException(ErrorCode.UNAUTHORIZED, "Endpoint réservé aux marchands");
        }
        return mid;
    }
}
