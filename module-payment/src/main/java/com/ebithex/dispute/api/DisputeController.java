package com.ebithex.dispute.api;

import com.ebithex.dispute.application.DisputeService;
import com.ebithex.dispute.dto.DisputeRequest;
import com.ebithex.dispute.dto.DisputeResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * API marchands pour les litiges.
 *
 * POST   /v1/disputes                  — ouvrir un litige
 * GET    /v1/disputes                  — lister mes litiges
 * GET    /v1/disputes/{id}             — détail d'un litige
 * DELETE /v1/disputes/{id}             — annuler un litige (OPEN uniquement)
 */
@RestController
@RequestMapping("/v1/disputes")
@RequiredArgsConstructor
@Tag(name = "Litiges (Disputes)")
public class DisputeController {

    private final DisputeService disputeService;

    @PostMapping
    @PreAuthorize("hasAnyRole('MERCHANT_KYC_VERIFIED')")
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeResponse openDispute(
            @Valid @RequestBody DisputeRequest req,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return DisputeResponse.from(disputeService.openDispute(req, principal.merchantId()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_KYC_VERIFIED')")
    public Page<DisputeResponse> listDisputes(
            @PageableDefault(size = 20, sort = "openedAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return disputeService.listForMerchant(principal.merchantId(), pageable)
            .map(DisputeResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_KYC_VERIFIED')")
    public DisputeResponse getDispute(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return DisputeResponse.from(disputeService.getForMerchant(id, principal.merchantId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<DisputeResponse> cancelDispute(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return ResponseEntity.ok(
            DisputeResponse.from(disputeService.cancelByMerchant(id, principal.merchantId())));
    }
}
