package com.ebithex.merchant.api;

import com.ebithex.merchant.application.MerchantService;
import com.ebithex.merchant.dto.MerchantProfileResponse;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
@Tag(name = "Marchands", description = "Profil, KYC et droits RGPD du marchand connecté")
public class MerchantController {

    private final MerchantService merchantService;

    @GetMapping("/me")
    @Operation(summary = "Profil du marchand connecté")
    public ResponseEntity<ApiResponse<MerchantProfileResponse>> getProfile(
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
            MerchantProfileResponse.from(merchantService.findById(principal.merchantId()))));
    }

    @PostMapping("/kyc")
    @Operation(summary = "Soumettre un dossier KYC")
    public ResponseEntity<ApiResponse<Void>> submitKyc(
            @AuthenticationPrincipal EbithexPrincipal principal) {
        merchantService.submitKyc(principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── RGPD ──────────────────────────────────────────────────────────────────

    /**
     * Export RGPD — droit d'accès (art. 15 RGPD).
     * Retourne toutes les données personnelles associées au compte connecté.
     */
    @GetMapping("/gdpr/export")
    @Operation(
        summary = "Export des données personnelles (RGPD art. 15)",
        description = "Retourne l'ensemble des données personnelles du marchand connecté " +
                      "conformément à l'article 15 du RGPD (droit d'accès). " +
                      "Les numéros de téléphone sont retournés sous forme chiffrée. " +
                      "L'export est tracé dans les journaux d'audit.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> exportGdprData(
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
            merchantService.exportGdprData(principal.merchantId())));
    }

    /**
     * Anonymisation RGPD — droit à l'effacement (art. 17 RGPD).
     * Anonymise les données personnelles identifiables du compte.
     * Irréversible. Les données financières sont conservées pour conformité légale (BCEAO).
     */
    @DeleteMapping("/gdpr/data")
    @Operation(
        summary = "Anonymisation des données personnelles (RGPD art. 17)",
        description = "Anonymise l'email et le nom commercial du marchand. " +
                      "Le compte est désactivé. **Irréversible.** " +
                      "Les transactions et wallets sont conservés conformément " +
                      "aux obligations légales de conservation financière (BCEAO — 10 ans).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Données anonymisées avec succès"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Non authentifié")
    })
    public ResponseEntity<ApiResponse<Void>> anonymizeGdprData(
            @AuthenticationPrincipal EbithexPrincipal principal) {
        merchantService.anonymizeGdprData(principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(
            "Données personnelles anonymisées. Votre compte a été désactivé.", (Void) null));
    }
}

