package com.ebithex.aml.dto;

import com.ebithex.aml.domain.AmlStatus;
import jakarta.validation.constraints.NotNull;

public record AmlReviewRequest(
    @NotNull AmlStatus status,
    String resolutionNote
) {}