package com.ebithex.payment.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour PaymentService.maskPhone().
 * Vérifie que le masquage préserve l'indicatif pays et expose uniquement les 4 derniers chiffres.
 */
@DisplayName("PaymentService — maskPhone()")
class PaymentServiceMaskPhoneTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "+22507001234,  +225****1234",   // CI (+225) — 12 chars
        "+221771234567, +221****4567",   // SN (+221) — 13 chars
        "+22990123456,  +229****3456",   // BJ (+229) — 11 chars
        "+254712345678, +254****5678",   // KE (+254) — 13 chars
        "+23480123456,  +234****3456",   // NG (+234) — 12 chars
        "+12345678901,  +123****8901",   // Format international long — 12 chars
    })
    @DisplayName("Format E.164 — conserve indicatif + masque milieu + expose 4 derniers chiffres")
    void maskPhone_e164(String input, String expected) {
        assertThat(PaymentService.maskPhone(input.trim())).isEqualTo(expected.trim());
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("null → null")
    void maskPhone_null_returnsNull(String input) {
        assertThat(PaymentService.maskPhone(input)).isNull();
    }

    @ParameterizedTest(name = "numéro court ''{0}'' → '****'")
    @CsvSource({ "+225", "12345", "123456" })
    @DisplayName("Numéro trop court (≤6 chars) → '****'")
    void maskPhone_tooShort_returnsStars(String input) {
        assertThat(PaymentService.maskPhone(input)).isEqualTo("****");
    }

    @ParameterizedTest(name = "PURGED ''{0}'' → masqué")
    @CsvSource({ "PURGED" })
    @DisplayName("Valeur 'PURGED' (PII purgé) → masquée sans exception")
    void maskPhone_purgedSentinel(String input) {
        // "PURGED" = 6 chars → isEqualTo("****")
        assertThat(PaymentService.maskPhone(input)).isEqualTo("****");
    }
}
