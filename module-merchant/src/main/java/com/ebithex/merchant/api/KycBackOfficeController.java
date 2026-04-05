package com.ebithex.merchant.api;

import com.ebithex.merchant.application.KycDocumentService;
import com.ebithex.merchant.domain.KycDocumentStatus;
import com.ebithex.merchant.dto.KycDocumentResponse;
import com.ebithex.merchant.dto.KycDocumentReviewRequest;
import com.ebithex.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Back-office KYC document review endpoints.
 * Restricted to ADMIN / SUPER_ADMIN / COUNTRY_ADMIN roles.
 */
@RestController
@RequestMapping("/internal/merchants")
@RequiredArgsConstructor
@Tag(name = "KYC Back-Office", description = "Review and validate merchant KYC documents")
public class KycBackOfficeController {

    private final KycDocumentService kycDocumentService;

    @Operation(summary = "List all KYC documents for a specific merchant")
    @GetMapping("/{merchantId}/kyc/documents")
    @PreAuthorize("hasAnyRole('COUNTRY_ADMIN','ADMIN','SUPER_ADMIN','SUPPORT')")
    public ResponseEntity<ApiResponse<List<KycDocumentResponse>>> listForMerchant(
            @PathVariable UUID merchantId) {

        return ResponseEntity.ok(
            ApiResponse.ok(kycDocumentService.listForMerchant(merchantId)));
    }

    @Operation(summary = "List KYC documents awaiting review (status=UPLOADED)")
    @GetMapping("/kyc/documents/pending")
    @PreAuthorize("hasAnyRole('COUNTRY_ADMIN','ADMIN','SUPER_ADMIN','SUPPORT')")
    public ResponseEntity<ApiResponse<List<KycDocumentResponse>>> listPending() {
        return ResponseEntity.ok(
            ApiResponse.ok(kycDocumentService.listForReview(KycDocumentStatus.UPLOADED)));
    }

    @Operation(summary = "Get a pre-signed URL for an admin to view a document")
    @GetMapping("/kyc/documents/{documentId}/url")
    @PreAuthorize("hasAnyRole('COUNTRY_ADMIN','ADMIN','SUPER_ADMIN','SUPPORT')")
    public ResponseEntity<ApiResponse<Map<String, String>>> presignedUrl(
            @PathVariable UUID documentId) {

        String url = kycDocumentService.adminPresignedUrl(documentId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url)));
    }

    @Operation(summary = "Accept or reject a KYC document")
    @PutMapping("/kyc/documents/{documentId}/review")
    @PreAuthorize("hasAnyRole('COUNTRY_ADMIN','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<KycDocumentResponse>> review(
            @PathVariable UUID documentId,
            @Valid @RequestBody KycDocumentReviewRequest request,
            Principal principal) {

        KycDocumentResponse response = kycDocumentService.review(
            documentId, request, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
