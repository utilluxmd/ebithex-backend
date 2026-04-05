package com.ebithex.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour la validation du type de token JWT dans JwtService.
 *
 * L'objectif est de vérifier que les types "access", "operator" et "refresh"
 * sont correctement distingués — le filtre ApiKeyAuthFilter utilise
 * extractTokenType() pour rejeter les refresh tokens.
 */
@DisplayName("JwtService — extractTokenType() discrimination access/refresh/operator")
class ApiKeyAuthFilterTokenTypeTest {

    private static final String SECRET = "b".repeat(48);
    private static final long EXP_MS   = 900_000L;
    private static final long REF_MS   = 604_800_000L;

    private final JwtService jwtService = new JwtService(SECRET, EXP_MS, REF_MS);

    @Test
    @DisplayName("Access token → type = 'access'")
    void accessToken_typeIsAccess() {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), "m@test.io");
        assertThat(jwtService.extractTokenType(token)).isEqualTo("access");
    }

    @Test
    @DisplayName("Refresh token → type = 'refresh' (doit être rejeté par le filtre)")
    void refreshToken_typeIsRefresh() {
        String token = jwtService.generateRefreshToken(UUID.randomUUID(), "m@test.io");
        assertThat(jwtService.extractTokenType(token)).isEqualTo("refresh");
        // Le filtre ApiKeyAuthFilter doit rejeter ce token comme Bearer pour les ressources :
        // seuls "access" et "operator" sont autorisés.
        assertThat(jwtService.extractTokenType(token)).isNotEqualTo("access");
        assertThat(jwtService.extractTokenType(token)).isNotEqualTo("operator");
    }

    @Test
    @DisplayName("Operator token → type = 'operator'")
    void operatorToken_typeIsOperator() {
        String token = jwtService.generateOperatorToken(
            UUID.randomUUID(), "admin@ebithex.io", List.of("ADMIN"), null);
        assertThat(jwtService.extractTokenType(token)).isEqualTo("operator");
    }
}
