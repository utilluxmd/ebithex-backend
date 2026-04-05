package com.ebithex.payout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class BulkPayoutRequest {

    @NotBlank(message = "La référence du lot est obligatoire")
    @Size(max = 100)
    private String merchantBatchReference;

    @Size(max = 255)
    private String label;

    @NotNull(message = "La liste des bénéficiaires est obligatoire")
    @Size(min = 1, message = "Le lot doit contenir au moins 1 bénéficiaire")
    @Size(max = 100, message = "Le lot ne peut pas dépasser 100 bénéficiaires")
    @Valid
    private List<BulkPayoutItemRequest> items;
}
