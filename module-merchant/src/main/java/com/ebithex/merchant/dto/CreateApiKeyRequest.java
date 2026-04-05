package com.ebithex.merchant.dto;

import com.ebithex.shared.apikey.ApiKeyScope;
import com.ebithex.shared.apikey.ApiKeyType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
public class CreateApiKeyRequest {

    @NotNull(message = "Le type de clé est obligatoire (LIVE ou TEST)")
    private ApiKeyType type;

    @Size(max = 100, message = "Le libellé ne peut pas dépasser 100 caractères")
    private String label;

    /**
     * Scopes autorisés. Si absent ou vide, FULL_ACCESS est appliqué par défaut.
     * Ex : ["PAYMENTS_WRITE", "PAYMENTS_READ"]
     */
    private Set<ApiKeyScope> scopes;

    /**
     * Liste d'IPs autorisées à utiliser cette clé.
     * Si absent ou vide, aucune restriction IP.
     * Ex : ["41.202.15.1", "41.202.15.2"]
     */
    private Set<String> allowedIps;

    /** Date d'expiration de la clé. Si absent, pas d'expiration. */
    private LocalDateTime expiresAt;
}
