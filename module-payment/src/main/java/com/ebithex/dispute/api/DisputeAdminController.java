package com.ebithex.dispute.api;

import com.ebithex.dispute.application.DisputeService;
import com.ebithex.dispute.domain.DisputeStatus;
import com.ebithex.dispute.dto.DisputeResolutionRequest;
import com.ebithex.dispute.dto.DisputeResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Back-office API pour la gestion des litiges.
 *
 * GET   /internal/disputes             — liste avec filtres
 * GET   /internal/disputes/{id}        — détail
 * PUT   /internal/disputes/{id}/review — prise en charge (OPEN → UNDER_REVIEW)
 * PUT   /internal/disputes/{id}/resolve — résolution finale
 */
@RestController
@RequestMapping("/internal/disputes")
@RequiredArgsConstructor
@Tag(name = "Back-office — Litiges")
public class DisputeAdminController {

    private final DisputeService disputeService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPPORT','COUNTRY_ADMIN','ADMIN','SUPER_ADMIN')")
    public Page<DisputeResponse> listDisputes(
            @RequestParam(required = false) DisputeStatus status,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 25, sort = "openedAt", direction = Sort.Direction.DESC) Pageable pageable) {

        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now();

        return disputeService
            .listForBackOffice(status, merchantId, effectiveFrom, effectiveTo, pageable)
            .map(DisputeResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPPORT','COUNTRY_ADMIN','ADMIN','SUPER_ADMIN')")
    public DisputeResponse getDispute(@PathVariable UUID id) {
        return DisputeResponse.from(disputeService.getById(id));
    }

    @PutMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('SUPPORT','COUNTRY_ADMIN','ADMIN','SUPER_ADMIN')")
    public DisputeResponse startReview(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return DisputeResponse.from(
            disputeService.startReview(id, principal != null ? principal.email() : "system"));
    }

    @PutMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPPORT','COUNTRY_ADMIN','ADMIN','SUPER_ADMIN')")
    public DisputeResponse resolveDispute(
            @PathVariable UUID id,
            @Valid @RequestBody DisputeResolutionRequest req,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        return DisputeResponse.from(
            disputeService.resolve(id, req, principal != null ? principal.email() : "system"));
    }
}
