package com.ebithex.webhook.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires pour WebhookUrlValidator — protection SSRF.
 *
 * <p>Stratégie DNS : les tests utilisent des adresses IP littérales dans les URLs
 * (pas de résolution DNS) pour les cas d'IP bloquées, et des noms d'hôtes
 * non-résolvables pour les cas de validation passante (la règle "non-résolvable = accepté"
 * est testée explicitement).
 */
@DisplayName("WebhookUrlValidator — Protection SSRF")
class WebhookUrlValidatorTest {

    private WebhookUrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WebhookUrlValidator();
    }

    // ── URL vide / nulle ───────────────────────────────────────────────────────

    @Test
    @DisplayName("validate(null) → IllegalArgumentException")
    void validate_null_throws() {
        assertThatThrownBy(() -> validator.validate(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vide");
    }

    @Test
    @DisplayName("validate(\"\") → IllegalArgumentException")
    void validate_blank_throws() {
        assertThatThrownBy(() -> validator.validate("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vide");
    }

    // ── Schéma non-HTTPS ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "Schéma {0} rejeté")
    @ValueSource(strings = {
        "http://example.com/webhook",
        "ftp://example.com/webhook",
        "file:///etc/passwd",
        "javascript:alert(1)"
    })
    @DisplayName("Schémas non-HTTPS → rejetés")
    void validate_nonHttpsScheme_throws(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS");
    }

    // ── Noms d'hôtes internes bloqués ────────────────────────────────────────

    @ParameterizedTest(name = "Hôte interne {0} rejeté")
    @ValueSource(strings = {
        "https://localhost/webhook",
        "https://api.local/webhook",
        "https://service.internal/webhook",
        "https://db.localdomain/webhook",
        "https://metadata.google.internal/webhook"
    })
    @DisplayName("Noms d'hôtes internes → rejetés")
    void validate_internalHostname_throws(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("interne");
    }

    // ── Ports non autorisés ───────────────────────────────────────────────────

    @ParameterizedTest(name = "Port {0} rejeté")
    @ValueSource(strings = {
        "https://nonexistent-host-xyz.com:80/webhook",
        "https://nonexistent-host-xyz.com:8080/webhook",
        "https://nonexistent-host-xyz.com:3000/webhook",
        "https://nonexistent-host-xyz.com:22/webhook"
    })
    @DisplayName("Ports non autorisés (non 443/8443) → rejetés")
    void validate_disallowedPort_throws(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Port non autorisé");
    }

    // ── IPs privées / loopback / link-local ───────────────────────────────────

    @ParameterizedTest(name = "IP bloquée {0}")
    @ValueSource(strings = {
        "https://127.0.0.1/webhook",          // loopback IPv4
        "https://10.0.0.1/webhook",            // RFC-1918 classe A
        "https://10.255.255.255/webhook",      // RFC-1918 classe A (borne haute)
        "https://172.16.0.1/webhook",          // RFC-1918 classe B
        "https://172.31.255.255/webhook",      // RFC-1918 classe B (borne haute)
        "https://192.168.1.100/webhook",       // RFC-1918 classe C
        "https://169.254.169.254/webhook",     // APIPA / metadata cloud
        "https://0.0.0.0/webhook"              // adresse indéfinie
    })
    @DisplayName("IPs privées/loopback/link-local → rejetées")
    void validate_privateOrLoopbackIp_throws(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non routable");
    }

    @Test
    @DisplayName("IPv6 loopback ::1 → rejeté")
    void validate_ipv6Loopback_throws() {
        assertThatThrownBy(() -> validator.validate("https://[::1]/webhook"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non routable");
    }

    @ParameterizedTest(name = "IPv6 ULA {0} rejeté")
    @ValueSource(strings = {
        "https://[fc00::1]/webhook",
        "https://[fd00::1]/webhook",
        "https://[fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]/webhook"
    })
    @DisplayName("IPv6 ULA fc00::/7 → rejetées")
    void validate_ipv6Ula_throws(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non routable");
    }

    // ── URLs valides ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("HTTPS sans port explicite (=443) + hôte non-résolvable → accepté")
    void validate_httpsNoPort_hostnameUnresolvable_accepted() {
        // L'hôte ne résout pas → warning log + acceptation (la livraison échouera)
        assertThatCode(() -> validator.validate("https://nonexistent-webhook-host-xyz-abc.com/callback"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HTTPS port 443 explicite + hôte non-résolvable → accepté")
    void validate_httpsPort443_accepted() {
        assertThatCode(() -> validator.validate("https://nonexistent-webhook-host-xyz-abc.com:443/webhook"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HTTPS port 8443 explicite + hôte non-résolvable → accepté")
    void validate_httpsPort8443_accepted() {
        assertThatCode(() -> validator.validate("https://nonexistent-webhook-host-xyz-abc.com:8443/api/webhook"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("URL avec chemin, query string et fragment → schéma/port/host seuls validés")
    void validate_urlWithPathAndQuery_accepted() {
        assertThatCode(() -> validator.validate(
            "https://nonexistent-webhook-host-xyz-abc.com/api/v1/webhooks?token=abc123"))
            .doesNotThrowAnyException();
    }
}
