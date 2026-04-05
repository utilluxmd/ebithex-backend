package com.ebithex.merchant.api;

import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.application.MerchantService;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.dto.ApiKeyResponse;
import com.ebithex.merchant.dto.MerchantAdminResponse;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints back-office pour la gestion des marchands.
 *
 * Accès contrôlé par SecurityConfig :
 *   GET/PUT /internal/merchants/** → COUNTRY_ADMIN, ADMIN, SUPER_ADMIN
 *
 * COUNTRY_ADMIN ne voit que les marchands de son pays (filtrage applicatif).
 */
@RestController
@RequestMapping("/internal/merchants")
@RequiredArgsConstructor
@Tag(name = "Back-office — Marchands")
public class BackOfficeController {

    private final MerchantService merchantService;
    private final ApiKeyService   apiKeyService;

    @GetMapping
    @Operation(summary = "Lister les marchands (filtré par pays pour COUNTRY_ADMIN)")
    public ResponseEntity<ApiResponse<Page<MerchantAdminResponse>>> list(
            @AuthenticationPrincipal EbithexPrincipal principal,
            Pageable pageable) {
        // COUNTRY_ADMIN ne voit que son périmètre
        String country = principal.hasCountryScope() ? principal.country() : null;
        Page<MerchantAdminResponse> page = merchantService
            .listMerchants(country, pageable)
            .map(MerchantAdminResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Détail d'un marchand")
    public ResponseEntity<ApiResponse<MerchantAdminResponse>> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        Merchant merchant = merchantService.findById(id);
        enforceCountryAccess(principal, merchant);
        return ResponseEntity.ok(ApiResponse.ok(MerchantAdminResponse.from(merchant)));
    }

    @PutMapping("/{id}/kyc/approve")
    @Operation(summary = "Approuver le KYC d'un marchand")
    public ResponseEntity<ApiResponse<Void>> approveKyc(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        enforceCountryAccess(principal, merchantService.findById(id));
        merchantService.approveKyc(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/{id}/kyc/reject")
    @Operation(summary = "Rejeter le KYC d'un marchand")
    public ResponseEntity<ApiResponse<Void>> rejectKyc(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal,
            @RequestBody Map<String, String> body) {
        enforceCountryAccess(principal, merchantService.findById(id));
        merchantService.rejectKyc(id, body.get("reason"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/{id}/activate")
    @Operation(summary = "Activer un compte marchand")
    public ResponseEntity<ApiResponse<Void>> activate(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        enforceCountryAccess(principal, merchantService.findById(id));
        merchantService.setActive(id, true);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/{id}/deactivate")
    @Operation(summary = "Désactiver un compte marchand")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        enforceCountryAccess(principal, merchantService.findById(id));
        merchantService.setActive(id, false);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Active/désactive le mode sandbox pour un marchand.
     * En mode sandbox, les paiements sont simulés — réservé SUPER_ADMIN.
     */
    @PutMapping("/{id}/test-mode")
    @Operation(summary = "Activer/désactiver le mode sandbox (SUPER_ADMIN uniquement)")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> setTestMode(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body) {
        Boolean testMode = body.get("testMode");
        if (testMode == null) {
            throw new EbithexException(ErrorCode.INVALID_REQUEST, "Le champ testMode est obligatoire");
        }
        merchantService.setTestMode(id, testMode);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Configure les plafonds journalier et mensuel d'un marchand.
     * Passer null pour supprimer un plafond.
     */
    @PutMapping("/{id}/limits")
    @Operation(summary = "Configurer les plafonds de paiement (journalier / mensuel)")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> setLimits(
            @PathVariable UUID id,
            @RequestBody Map<String, BigDecimal> body) {
        merchantService.setPaymentLimits(id, body.get("dailyLimit"), body.get("monthlyLimit"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Gestion des clés API ──────────────────────────────────────────────────

    @GetMapping("/{id}/api-keys")
    @Operation(summary = "Lister toutes les clés API d'un marchand",
               description = "Retourne les clés actives et inactives. Rôles : ADMIN · SUPER_ADMIN")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<ApiKeyResponse>>> listApiKeys(@PathVariable UUID id) {
        List<ApiKeyResponse> keys = apiKeyService.listAllKeysForMerchant(id)
            .stream().map(ApiKeyResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(keys));
    }

    /**
     * Révocation d'urgence de toutes les clés API d'un marchand (live + test).
     * La révocation est immédiate — aucune période de grâce n'est appliquée.
     * Le marchand devra créer de nouvelles clés via POST /v1/auth/api-keys.
     */
    @PostMapping("/{id}/api-key/revoke")
    @Operation(summary = "Révocation d'urgence de toutes les clés API",
               description = "Révocation immédiate et totale — clés actives + période de grâce. " +
                             "Le marchand doit créer de nouvelles clés via POST /v1/auth/api-keys. " +
                             "Tracé dans audit_logs (API_KEYS_REVOKED). Rôles : ADMIN · SUPER_ADMIN")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> revokeAllApiKeys(@PathVariable UUID id) {
        merchantService.revokeAllApiKeys(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Configure la politique de rotation forcée sur une clé spécifique.
     * Passer null pour supprimer la contrainte.
     */
    @PutMapping("/{id}/api-keys/{keyId}/rotation-policy")
    @Operation(summary = "Configurer la rotation forcée d'une clé",
               description = "Impose un nombre maximal de jours avant désactivation automatique. " +
                             "Passer `rotationRequiredDays: null` pour supprimer la contrainte. Rôles : SUPER_ADMIN")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> setRotationPolicy(
            @PathVariable UUID id,
            @PathVariable UUID keyId,
            @RequestBody Map<String, Integer> body) {
        apiKeyService.setRotationPolicy(keyId, body.get("rotationRequiredDays"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void enforceCountryAccess(EbithexPrincipal principal, Merchant merchant) {
        if (principal.hasCountryScope() && !principal.country().equals(merchant.getCountry())) {
            throw new EbithexException(ErrorCode.ACCESS_DENIED,
                "COUNTRY_ADMIN limité au pays: " + principal.country());
        }
    }
}