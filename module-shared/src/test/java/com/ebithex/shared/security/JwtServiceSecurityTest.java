package com.ebithex.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests de validation de la longueur minimale des secrets JWT.
 */
@DisplayName("JwtService — Validation longueur minimale des secrets")
class JwtServiceSecurityTest {

    private static final long EXP_MS = 900_000L;

    @Test
    @DisplayName("Secret de 32 octets (minimum) → construction réussie")
    void constructor_secret32Bytes_succeeds() {
        // 32 octets = minimum requis pour HMAC-SHA256
        String secret32 = "12345678901234567890123456789012"; // exactement 32 chars ASCII
        assertThatCode(() -> new JwtService(secret32, EXP_MS, 604800000L))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Secret de 64 octets → construction réussie")
    void constructor_secret64Bytes_succeeds() {
        String secret64 = "a".repeat(64);
        assertThatCode(() -> new JwtService(secret64, EXP_MS, 604800000L))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Secret de 31 octets (trop court) → IllegalArgumentException")
    void constructor_secret31Bytes_throwsIllegalArgument() {
        String secret31 = "1234567890123456789012345678901"; // 31 chars
        assertThatThrownBy(() -> new JwtService(secret31, EXP_MS, 604800000L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trop court")
            .hasMessageContaining("index 0");
    }

    @Test
    @DisplayName("Secret vide → IllegalArgumentException")
    void constructor_emptySecret_throwsIllegalArgument() {
        assertThatThrownBy(() -> new JwtService("", EXP_MS, 604800000L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rotation multi-secrets : clé active ok, clé de grâce trop courte → exception")
    void constructor_gracePeriodSecretTooShort_throwsIllegalArgument() {
        String validSecret = "a".repeat(48);
        String shortSecret = "trop-court"; // < 32 octets
        String csv = validSecret + "," + shortSecret;

        assertThatThrownBy(() -> new JwtService(csv, EXP_MS, 604800000L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("index 1");
    }

    @Test
    @DisplayName("Rotation multi-secrets valides → construction réussie")
    void constructor_multipleValidSecrets_succeeds() {
        String s1 = "a".repeat(48);
        String s2 = "b".repeat(32);
        String csv = s1 + "," + s2;

        assertThatCode(() -> new JwtService(csv, EXP_MS, 604800000L))
            .doesNotThrowAnyException();
    }
}
