package com.ebithex.merchant.dto;

import com.ebithex.merchant.domain.ApiKey;
import com.ebithex.shared.apikey.ApiKeyScope;
import com.ebithex.shared.apikey.ApiKeyType;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Représentation publique d'une clé API.
 * La valeur brute n'est jamais incluse ici — elle est retournée UNE SEULE FOIS
 * à la création ou à la rotation via {@link ApiKeyCreatedResponse}.
 */
@Builder
public record ApiKeyResponse(
    UUID id,
    ApiKeyType type,
    String prefix,
    String hint,          // ex. "...xK3a" (4 derniers chars)
    String label,
    Set<ApiKeyScope> scopes,
    List<String> allowedIps,  // liste vide = pas de restriction
    LocalDateTime expiresAt,
    LocalDateTime lastUsedAt,
    LocalDateTime createdAt,
    boolean active,
    Integer rotationRequiredDays,
    boolean expired          // commodité : expiresAt != null && expiresAt < now
) {

    public static ApiKeyResponse from(ApiKey key) {
        List<String> ips = (key.getAllowedIps() == null || key.getAllowedIps().isBlank())
            ? List.of()
            : Arrays.stream(key.getAllowedIps().split(","))
                    .map(String::trim)
                    .collect(Collectors.toList());

        return ApiKeyResponse.builder()
            .id(key.getId())
            .type(key.getType())
            .prefix(key.getPrefix())
            .hint(key.getKeyHint())
            .label(key.getLabel())
            .scopes(key.parsedScopes())
            .allowedIps(ips)
            .expiresAt(key.getExpiresAt())
            .lastUsedAt(key.getLastUsedAt())
            .createdAt(key.getCreatedAt())
            .active(key.isActive())
            .rotationRequiredDays(key.getRotationRequiredDays())
            .expired(key.isExpired())
            .build();
    }

    /**
     * Réponse enrichie retournée UNE SEULE FOIS lors de la création ou de la rotation.
     * Inclut la valeur brute de la clé.
     */
    @Builder
    public record ApiKeyCreatedResponse(
        UUID id,
        String rawKey,        // valeur brute — affiché une seule fois
        ApiKeyType type,
        String prefix,
        String hint,
        String label,
        Set<ApiKeyScope> scopes,
        List<String> allowedIps,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        Integer gracePeriodHours   // heures pendant lesquelles l'ancienne clé reste valide
    ) {
        public static ApiKeyCreatedResponse from(ApiKey key, String rawKey, int gracePeriodHours) {
            List<String> ips = (key.getAllowedIps() == null || key.getAllowedIps().isBlank())
                ? List.of()
                : Arrays.stream(key.getAllowedIps().split(","))
                        .map(String::trim)
                        .collect(Collectors.toList());

            return ApiKeyCreatedResponse.builder()
                .id(key.getId())
                .rawKey(rawKey)
                .type(key.getType())
                .prefix(key.getPrefix())
                .hint(key.getKeyHint())
                .label(key.getLabel())
                .scopes(key.parsedScopes())
                .allowedIps(ips)
                .expiresAt(key.getExpiresAt())
                .createdAt(key.getCreatedAt())
                .gracePeriodHours(gracePeriodHours)
                .build();
        }
    }
}
