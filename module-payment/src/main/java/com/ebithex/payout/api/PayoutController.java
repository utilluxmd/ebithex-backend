package com.ebithex.payout.api;

import com.ebithex.payout.application.PayoutService;
import com.ebithex.payout.dto.PayoutRequest;
import com.ebithex.payout.dto.PayoutResponse;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payouts")
@RequiredArgsConstructor
@Validated
@Tag(name = "Payouts", description = "Cash-out : décaissements vers portefeuilles Mobile Money")
@SecurityRequirement(name = "ApiKey")
public class PayoutController {

    private final PayoutService payoutService;

    @PostMapping
    @Operation(summary = "Initier un payout (cash-out)",
               description = """
                   Envoie des fonds du solde marchand vers un portefeuille Mobile Money. Requiert KYC vérifié.

                   **Idempotence** : deux mécanismes sont supportés :
                   - Header `Idempotency-Key` (standard REST) : valeur arbitraire ≤ 64 caractères fournie par le client.
                     Si la même clé est renvoyée, la réponse originale est retournée sans re-traitement.
                   - Champ `merchantReference` dans le body : comportement identique.

                   En cas de replay idempotent, le header `Idempotent-Replayed: true` est ajouté à la réponse.
                   """)
    public ResponseEntity<ApiResponse<PayoutResponse>> initiatePayout(
            @Valid @RequestBody PayoutRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        // Si Idempotency-Key fourni et merchantReference absent, utiliser la clé comme merchantReference
        if (idempotencyKey != null && !idempotencyKey.isBlank()
                && (request.getMerchantReference() == null || request.getMerchantReference().isBlank())) {
            request.setMerchantReference(idempotencyKey);
        }

        PayoutResponse response = payoutService.initiatePayout(request, principal.merchantId());

        ResponseEntity.BodyBuilder builder;
        if (response.getMessage() != null && response.getMessage().contains("idempotente")) {
            builder = ResponseEntity.ok().header("Idempotent-Replayed", "true");
        } else {
            builder = ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .header("Location", "/api/v1/payouts/" + response.getEbithexReference());
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder.body(ApiResponse.ok("Payout initié", response));
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Statut d'un payout")
    public ResponseEntity<ApiResponse<PayoutResponse>> getStatus(
            @PathVariable String reference,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        PayoutResponse response = payoutService.getStatus(reference, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @Operation(summary = "Lister les payouts")
    public ResponseEntity<ApiResponse<Page<PayoutResponse>>> listPayouts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<PayoutResponse> result = payoutService.listPayouts(principal.merchantId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
