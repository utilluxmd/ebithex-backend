package com.ebithex.payment;

import com.ebithex.payment.infrastructure.PhoneNumberUtil;
import com.ebithex.shared.domain.OperatorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour la détection d'opérateur et la normalisation des numéros.
 * Aucun contexte Spring — test pur de la logique métier.
 */
@DisplayName("PhoneNumberUtil — Détection d'opérateur et normalisation")
class PhoneNumberUtilTest {

    private PhoneNumberUtil phoneUtil;

    @BeforeEach
    void setUp() {
        phoneUtil = new PhoneNumberUtil();
    }

    // ── Détection d'opérateur par pays ───────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // Côte d'Ivoire — +225 + 0 + [préfixe] + 7 chiffres = 12 chiffres après +
        "+225051234567, MTN_MOMO_CI",       // MTN CI (préfixe 05)
        "+225071234567, MTN_MOMO_CI",       // MTN CI (préfixe 07)
        "+225041234567, ORANGE_MONEY_CI",   // Orange CI (préfixe 04)
        "+225021234567, WAVE_CI",           // Wave CI (préfixe 02)
        // Sénégal — +221 + 7 + [78] + 7 chiffres = 12 chiffres après +
        "+221771234567, ORANGE_MONEY_SN",   // Orange SN (préfixe 77)
        "+221371234567, WAVE_SN",           // Wave SN (préfixe 37)
        // Bénin — +229 + 9 + [67] + 6 chiffres = 11 chiffres après +
        "+22996123456, MTN_MOMO_BJ",        // MTN BJ (préfixe 96)
    })
    @DisplayName("Détecte le bon opérateur pour les numéros E.164 valides")
    void detectOperator_knownPrefixes(String phone, OperatorType expected) {
        assertThat(phoneUtil.detectOperator(phone)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Retourne null pour un numéro sans opérateur connu")
    void detectOperator_unknownPrefix_returnsNull() {
        assertThat(phoneUtil.detectOperator("+33612345678")).isNull();  // France
    }

    @Test
    @DisplayName("Retourne null pour un numéro null")
    void detectOperator_null_returnsNull() {
        assertThat(phoneUtil.detectOperator(null)).isNull();
    }

    // ── Normalisation E.164 ──────────────────────────────────────────────────

    @Test
    @DisplayName("Normalise un numéro déjà en E.164 sans modification")
    void normalizePhone_e164_unchanged() {
        String result = phoneUtil.normalizePhone("+225051234567");
        assertThat(result).isEqualTo("+225051234567");
    }

    @Test
    @DisplayName("Supprime les espaces et tirets")
    void normalizePhone_stripsSpaces() {
        String result = phoneUtil.normalizePhone("+225 05 123 456 7");
        assertThat(result).isNotNull();
        assertThat(result).doesNotContain(" ");
    }

    @Test
    @DisplayName("Retourne le numéro nettoyé si normalisation E.164 impossible")
    void normalizePhone_nonE164_returnsCleaned() {
        String result = phoneUtil.normalizePhone("0512345678");
        assertThat(result).isNotNull();
        assertThat(result).doesNotContain(" ");
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valide un numéro E.164 correct")
    void isValid_e164_returnsTrue() {
        assertThat(phoneUtil.isValid("+22177123456789")).isFalse(); // trop long
        assertThat(phoneUtil.isValid("+221771234567")).isTrue();   // SN valide 9 digits
    }

    @Test
    @DisplayName("Invalide un numéro null ou vide")
    void isValid_nullOrBlank_returnsFalse() {
        assertThat(phoneUtil.isValid(null)).isFalse();
        assertThat(phoneUtil.isValid("")).isFalse();
        assertThat(phoneUtil.isValid("   ")).isFalse();
    }
}