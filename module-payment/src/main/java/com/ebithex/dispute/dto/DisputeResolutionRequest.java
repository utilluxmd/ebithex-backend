package com.ebithex.dispute.dto;

import com.ebithex.dispute.domain.DisputeStatus;
import jakarta.validation.constraints.NotNull;

public record DisputeResolutionRequest(
    @NotNull DisputeStatus status,
    String resolutionNotes
) {}