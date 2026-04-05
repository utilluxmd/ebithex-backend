package com.ebithex.payout.api;

import com.ebithex.payout.application.BulkPayoutService;
import com.ebithex.payout.dto.BulkPayoutRequest;
import com.ebithex.payout.dto.BulkPayoutResponse;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payouts/bulk")
@RequiredArgsConstructor
@Tag(name = "Bulk Payouts", description = "Cash-out groupé : décaissements simultanés vers plusieurs bénéficiaires (masse salariale, remboursements)")
@SecurityRequirement(name = "ApiKey")
public class BulkPayoutController {

    private final BulkPayoutService bulkPayoutService;

    @PostMapping
    @Operation(summary = "Créer un lot de décaissements",
               description = "Crée et déclenche le traitement asynchrone d'un lot cash-out (max 100 bénéficiaires). " +
                             "Retourne immédiatement — suivre via GET /v1/payouts/bulk/{ref}.")
    public ResponseEntity<ApiResponse<BulkPayoutResponse>> createBulk(
            @Valid @RequestBody BulkPayoutRequest request,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        BulkPayoutResponse response = bulkPayoutService.createBulk(request, principal.merchantId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok("Lot créé — décaissements en cours", response));
    }

    @GetMapping("/{batchReference}")
    @Operation(summary = "Statut d'un lot de décaissements")
    public ResponseEntity<ApiResponse<BulkPayoutResponse>> getBatch(
            @PathVariable String batchReference,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        BulkPayoutResponse response = bulkPayoutService.getBatch(batchReference, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{batchReference}/items")
    @Operation(summary = "Détail des bénéficiaires d'un lot",
               description = "Retourne le statut du décaissement pour chaque bénéficiaire.")
    public ResponseEntity<ApiResponse<BulkPayoutResponse>> getBatchItems(
            @PathVariable String batchReference,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        BulkPayoutResponse response = bulkPayoutService.getBatchItems(batchReference, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @Operation(summary = "Lister les lots de décaissements")
    public ResponseEntity<ApiResponse<Page<BulkPayoutResponse>>> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<BulkPayoutResponse> result = bulkPayoutService.listBatches(principal.merchantId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
