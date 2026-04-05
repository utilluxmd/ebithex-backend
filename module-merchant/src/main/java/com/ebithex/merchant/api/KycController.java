package com.ebithex.merchant.api;

import com.ebithex.merchant.application.KycDocumentService;
import com.ebithex.merchant.domain.KycDocumentType;
import com.ebithex.merchant.dto.KycDocumentResponse;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merchant-facing KYC document endpoints.
 * All routes require authentication (API Key or JWT).
 */
@RestController
@RequestMapping("/v1/merchants/kyc/documents")
@RequiredArgsConstructor
@Tag(name = "KYC Documents", description = "Upload and manage KYC documents")
public class KycController {

    private final KycDocumentService kycDocumentService;

    @Operation(summary = "Upload a KYC document")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KycDocumentResponse>> upload(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @RequestParam("type") KycDocumentType documentType,
            @RequestParam("file") MultipartFile file) {

        KycDocumentResponse response = kycDocumentService.upload(
            principal.merchantId(), documentType, file);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "List all KYC documents for the authenticated merchant")
    @GetMapping
    public ResponseEntity<ApiResponse<List<KycDocumentResponse>>> list(
            @AuthenticationPrincipal EbithexPrincipal principal) {

        return ResponseEntity.ok(
            ApiResponse.ok(kycDocumentService.list(principal.merchantId())));
    }

    @Operation(summary = "Get a short-lived pre-signed download URL for a document")
    @GetMapping("/{documentId}/url")
    public ResponseEntity<ApiResponse<Map<String, String>>> presignedUrl(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PathVariable UUID documentId) {

        String url = kycDocumentService.presignedUrl(principal.merchantId(), documentId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url)));
    }

    @Operation(summary = "Soft-delete a KYC document (only allowed before review)")
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PathVariable UUID documentId) {

        kycDocumentService.softDelete(principal.merchantId(), documentId);
        return ResponseEntity.noContent().build();
    }
}
