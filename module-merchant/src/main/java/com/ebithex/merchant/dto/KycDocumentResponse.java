package com.ebithex.merchant.dto;

import com.ebithex.merchant.domain.KycDocumentStatus;
import com.ebithex.merchant.domain.KycDocumentType;

import java.time.Instant;
import java.util.UUID;

public record KycDocumentResponse(
    UUID              id,
    KycDocumentType   documentType,
    KycDocumentStatus status,
    String            fileName,
    String            contentType,
    long              fileSizeBytes,
    String            providerName,
    String            reviewerNotes,
    Instant           uploadedAt,
    Instant           expiresAt
) {}
