package com.ebithex.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires pour JwtService — issuer et type de token.
 */
@DisplayName("JwtService — Issuer et type de token")
class JwtServiceTokenTypeTest {

    private static final String SECRET    = "a".repeat(48);
    private static final long   EXP_MS    = 900_000L;
    private static final long   REF_MS    = 604_800_000L;

    private JwtService service;

    @BeforeEach
    void setUp() {
        service = new JwtService(SECRET, EXP_MS, REF_MS);
    }

    // ── Issuer claim ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateAccessToken — contient claim iss=ebithex-backend")
    void accessToken_hasIssuerClaim() {
        String token = service.generateAccessToken(UUID.randomUUID(), "merchant@test.io");
        assertThat(service.extractTokenType(token)).isEqualTo("access");
        // validateToken passe l'issuer = on vérifie via extraction directe
        assertThat(extractIssuer(token)).isEqualTo(JwtService.ISSUER);
    }

    @Test
    @DisplayName("generateOperatorToken — contient claim iss=ebithex-backend")
    void operatorToken_hasIssuerClaim() {
        String token = service.generateOperatorToken(UUID.randomUUID(), "admin@ebithex.io",
            List.of("ADMIN"), null);
        assertThat(extractIssuer(token)).isEqualTo(JwtService.ISSUER);
    }

    @Test
    @DisplayName("generateRefreshToken — contient claim iss=ebithex-backend")
    void refreshToken_hasIssuerClaim() {
        String token = service.generateRefreshToken(UUID.randomUUID(), "merchant@test.io");
        assertThat(extractIssuer(token)).isEqualTo(JwtService.ISSUER);
    }

    @Test
    @DisplayName("Token avec mauvais issuer → MalformedJwtException")
    void wrongIssuer_throwsMalformedJwt() {
        // Construire manuellement un token signé mais avec iss=wrong-issuer
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String tokenWrongIss = Jwts.builder()
            .issuer("wrong-issuer")
            .subject(UUID.randomUUID().toString())
            .claim("type", "access")
            .claim("email", "x@x.io")
            .expiration(new Date(System.currentTimeMillis() + EXP_MS))
            .signWith(key)
            .compact();

        // validateToken doit retourner false (l'exception est attrapée en interne)
        assertThat(service.validateToken(tokenWrongIss)).isFalse();
    }

    @Test
    @DisplayName("Token sans issuer (legacy pré-migration) → validé sans erreur")
    void tokenWithoutIssuer_validatedWithoutError() {
        // Token legacy : pas de claim iss → toléré pendant la période de grâce
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String legacyToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("type", "access")
            .claim("email", "merchant@test.io")
            .expiration(new Date(System.currentTimeMillis() + EXP_MS))
            .signWith(key)
            .compact();

        assertThat(service.validateToken(legacyToken)).isTrue();
    }

    // ── Type de token ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("extractTokenType — access")
    void extractTokenType_access() {
        String token = service.generateAccessToken(UUID.randomUUID(), "m@test.io");
        assertThat(service.extractTokenType(token)).isEqualTo("access");
    }

    @Test
    @DisplayName("extractTokenType — refresh")
    void extractTokenType_refresh() {
        String token = service.generateRefreshToken(UUID.randomUUID(), "m@test.io");
        assertThat(service.extractTokenType(token)).isEqualTo("refresh");
    }

    @Test
    @DisplayName("extractTokenType — operator")
    void extractTokenType_operator() {
        String token = service.generateOperatorToken(
            UUID.randomUUID(), "admin@ebithex.io", List.of("ADMIN"), null);
        assertThat(service.extractTokenType(token)).isEqualTo("operator");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Extrait l'issuer depuis un token valide sans re-valider la signature (pour les tests). */
    private String extractIssuer(String token) {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).getPayload();
        return claims.getIssuer();
    }
}
