package com.ebithex.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service JWT avec support de rotation de clés.
 *
 * Stratégie de rotation :
 *  - Plusieurs secrets peuvent coexister : le premier de la liste est la clé active (signature).
 *  - Les clés suivantes sont les anciennes clés (vérification seulement — période de grâce).
 *  - Chaque token porte un claim d'en-tête {@code kid} qui référence son ID de clé.
 *  - Lors de la vérification, la clé correspondante est sélectionnée via {@code kid}.
 *    Si aucun {@code kid} n'est présent (tokens legacy), on essaie toutes les clés dans l'ordre.
 *
 * Configuration :
 *  ebithex.security.jwt.secrets=secret1,secret2   # virgule-séparée, index 0 = clé active
 *  ebithex.security.jwt.expiration=900000          # access token TTL ms
 *  ebithex.security.jwt.refresh-expiration=604800000
 */
@Service
@Slf4j
public class JwtService {

    /** Clés indexées : index 0 = active, index N = grâce. kid = index en String. */
    /** Issuer standard RFC 7519 — porté dans le claim {@code iss} de chaque token. */
    static final String ISSUER = "ebithex-backend";

    private final Map<String, SecretKey> keyMap;
    /** kid de la clé active (celle utilisée pour signer les nouveaux tokens). */
    private final String activeKid;
    private final long expirationMs;
    private final long refreshExpirationMs;

    public JwtService(
        @Value("${ebithex.security.jwt.secrets}") String secretsCsv,
        @Value("${ebithex.security.jwt.expiration}") long expirationMs,
        @Value("${ebithex.security.jwt.refresh-expiration:604800000}") long refreshExpirationMs
    ) {
        String[] secrets = secretsCsv.split(",");
        if (secrets.length == 0 || secrets[0].isBlank()) {
            throw new IllegalArgumentException("ebithex.security.jwt.secrets ne peut pas être vide");
        }
        // LinkedHashMap pour conserver l'ordre d'insertion (0 = actif)
        Map<String, SecretKey> map = new LinkedHashMap<>();
        for (int i = 0; i < secrets.length; i++) {
            String secret   = secrets[i].trim();
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) {
                throw new IllegalArgumentException(
                    "JWT secret à l'index " + i + " trop court : " + keyBytes.length
                    + " octets (minimum 32 requis pour HMAC-SHA256). "
                    + "Générer avec : openssl rand -base64 32");
            }
            String kid = String.valueOf(i);
            map.put(kid, Keys.hmacShaKeyFor(keyBytes));
        }
        this.keyMap = Collections.unmodifiableMap(map);
        this.activeKid = "0";
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(UUID merchantId, String email) {
        return Jwts.builder()
            .header().add("kid", activeKid).and()
            .id(UUID.randomUUID().toString())
            .issuer(ISSUER)
            .subject(merchantId.toString())
            .claim("email", email)
            .claim("type", "access")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(activeKey())
            .compact();
    }

    /**
     * Génère un token pour un opérateur back-office.
     * Le claim "roles" contient la liste des noms de rôles (ex : ["ADMIN"]).
     * Le claim "country" est non-null uniquement pour COUNTRY_ADMIN.
     * Type = "operator" — distingué du token marchand dans ApiKeyAuthFilter.
     */
    public String generateOperatorToken(UUID operatorId, String email, List<String> roles, @Nullable String country) {
        var builder = Jwts.builder()
            .header().add("kid", activeKid).and()
            .id(UUID.randomUUID().toString())
            .issuer(ISSUER)
            .subject(operatorId.toString())
            .claim("email", email)
            .claim("type", "operator")
            .claim("roles", roles)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(activeKey());
        if (country != null) {
            builder.claim("country", country);
        }
        return builder.compact();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object roles = parseToken(token).get("roles");
        return roles instanceof List ? (List<String>) roles : List.of();
    }

    /** Retourne le pays de l'opérateur, ou {@code null} si ce n'est pas un COUNTRY_ADMIN. */
    @Nullable
    public String extractCountry(String token) {
        return (String) parseToken(token).get("country");
    }

    public String generateRefreshToken(UUID merchantId, String email) {
        return Jwts.builder()
            .header().add("kid", activeKid).and()
            .id(UUID.randomUUID().toString())
            .issuer(ISSUER)
            .subject(merchantId.toString())
            .claim("email", email)
            .claim("type", "refresh")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
            .signWith(activeKey())
            .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
        }
        return false;
    }

    public UUID extractMerchantId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String extractEmail(String token) {
        return (String) parseToken(token).get("email");
    }

    /** Extrait le JTI (JWT ID) du token — token doit être valide et non expiré. */
    public String getJti(String token) {
        return parseToken(token).getId();
    }

    /** Extrait la date d'expiration du token — token doit être valide et non expiré. */
    public Date getExpiration(String token) {
        return parseToken(token).getExpiration();
    }

    /** Extrait le type de token ("access", "refresh" ou "operator"). */
    public String extractTokenType(String token) {
        return (String) parseToken(token).get("type");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SecretKey activeKey() {
        return keyMap.get(activeKid);
    }

    /**
     * Parse et vérifie le token en sélectionnant la clé via le claim {@code kid}.
     * Si aucun {@code kid} n'est présent (tokens pré-rotation), essaie toutes les clés.
     */
    private Claims parseToken(String token) {
        String kid = extractKidFromHeader(token);
        if (kid != null && keyMap.containsKey(kid)) {
            return parseWithKey(token, keyMap.get(kid));
        }
        // Tokens legacy sans kid : on essaie dans l'ordre
        JwtException lastException = null;
        for (SecretKey key : keyMap.values()) {
            try {
                return parseWithKey(token, key);
            } catch (JwtException e) {
                lastException = e;
            }
        }
        throw lastException != null ? lastException
            : new MalformedJwtException("Token invalide — aucune clé ne correspond");
    }

    private Claims parseWithKey(String token, SecretKey key) {
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        // Valider l'issuer si le claim est présent.
        // Les tokens pré-migration (sans claim iss) sont tolérés pendant la période de grâce
        // (durée = max(expiration access token, expiration refresh token) = 7 jours après déploiement).
        // TODO : rendre la validation obligatoire à partir du 12 avril 2026 (7 jours post-déploiement)
        String iss = claims.getIssuer();
        if (iss != null && !ISSUER.equals(iss)) {
            throw new MalformedJwtException(
                "JWT issuer invalide: attendu '" + ISSUER + "', reçu '" + iss + "'");
        }
        return claims;
    }

    /**
     * Extrait le {@code kid} depuis l'en-tête JWT sans vérifier la signature.
     * Décode le premier segment Base64url du token et lit le champ "kid".
     * Retourne {@code null} si l'en-tête est absent ou si le token est malformé.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private String extractKidFromHeader(String token) {
        try {
            int dotIndex = token.indexOf('.');
            if (dotIndex < 0) return null;
            String headerB64 = token.substring(0, dotIndex);
            byte[] headerBytes = Base64.getUrlDecoder().decode(headerB64);
            Map<String, Object> header = new ObjectMapper()
                .readValue(headerBytes, Map.class);
            Object kid = header.get("kid");
            return kid instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
