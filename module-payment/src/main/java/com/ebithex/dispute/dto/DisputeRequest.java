package com.ebithex.dispute.dto;

import com.ebithex.dispute.domain.DisputeReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DisputeRequest(
    @NotBlank String ebithexReference,
    @NotNull  DisputeReason reason,
    String description
) {}