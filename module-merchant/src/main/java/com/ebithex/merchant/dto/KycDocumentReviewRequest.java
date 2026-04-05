package com.ebithex.merchant.dto;

import jakarta.validation.constraints.NotNull;
import com.ebithex.merchant.domain.KycDocumentStatus;

public record KycDocumentReviewRequest(
    @NotNull KycDocumentStatus status,
    String notes
) {}
