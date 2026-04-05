package com.ebithex.shared;

import com.ebithex.shared.util.HmacUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour HmacUtil — signature HMAC-SHA256.
 * Aucun contexte Spring — test pur de la logique cryptographique.
 */
@DisplayName("HmacUtil — Signature et vérification HMAC-SHA256")
class HmacUtilTest {

    private HmacUtil hmacUtil;
    private static final String SECRET  = "test-secret-key-256bits-ebithex!!";
    private static final String PAYLOAD = "{\"event\":\"payment.success\",\"amount\":10000}";

    @BeforeEach
    void setUp() {
        hmacUtil = new HmacUtil();
    }

    @Test
    @DisplayName("sign() retourne une signature préfixée 'sha256='")
    void sign_returnsSignatureWithPrefix() {
        String sig = hmacUtil.sign(PAYLOAD, SECRET);

        assertThat(sig).startsWith("sha256=");
        assertThat(sig).hasSize(71); // "sha256=" (7) + 64 hex chars
    }

    @Test
    @DisplayName("sign() est déterministe — même payload + secret → même signature")
    void sign_isDeterministic() {
        String sig1 = hmacUtil.sign(PAYLOAD, SECRET);
        String sig2 = hmacUtil.sign(PAYLOAD, SECRET);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("sign() produit des signatures différentes pour des secrets différents")
    void sign_differentSecrets_produceDifferentSignatures() {
        String sig1 = hmacUtil.sign(PAYLOAD, "secret-one");
        String sig2 = hmacUtil.sign(PAYLOAD, "secret-two");

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("sign() produit des signatures différentes pour des payloads différents")
    void sign_differentPayloads_produceDifferentSignatures() {
        String sig1 = hmacUtil.sign("{\"amount\":100}", SECRET);
        String sig2 = hmacUtil.sign("{\"amount\":200}", SECRET);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("verify() retourne true pour un payload + secret corrects")
    void verify_validSignature_returnsTrue() {
        String signature = hmacUtil.sign(PAYLOAD, SECRET);

        assertThat(hmacUtil.verify(PAYLOAD, SECRET, signature)).isTrue();
    }

    @Test
    @DisplayName("verify() retourne false si le payload a été altéré")
    void verify_tamperedPayload_returnsFalse() {
        String signature = hmacUtil.sign(PAYLOAD, SECRET);
        String tampered  = PAYLOAD.replace("10000", "99999");

        assertThat(hmacUtil.verify(tampered, SECRET, signature)).isFalse();
    }

    @Test
    @DisplayName("verify() retourne false si le secret est incorrect")
    void verify_wrongSecret_returnsFalse() {
        String signature = hmacUtil.sign(PAYLOAD, SECRET);

        assertThat(hmacUtil.verify(PAYLOAD, "wrong-secret", signature)).isFalse();
    }

    @Test
    @DisplayName("verify() retourne false si la signature est altérée")
    void verify_tamperedSignature_returnsFalse() {
        String signature = hmacUtil.sign(PAYLOAD, SECRET);
        String tampered  = signature.replace(signature.charAt(10), 'x');

        assertThat(hmacUtil.verify(PAYLOAD, SECRET, tampered)).isFalse();
    }

    @Test
    @DisplayName("sign() fonctionne avec un payload vide")
    void sign_emptyPayload_returnsValidSignature() {
        String sig = hmacUtil.sign("", SECRET);

        assertThat(sig).startsWith("sha256=");
        assertThat(hmacUtil.verify("", SECRET, sig)).isTrue();
    }
}