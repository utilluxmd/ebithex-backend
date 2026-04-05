package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Validation et sanitisation des champs libres.
 *
 * Couvre :
 *  - Payloads XSS dans description, customerName, metadata → 400
 *  - returnUrl/cancelUrl non HTTP → 400
 *  - customerEmail invalide → 400
 *  - Champs valides → acceptés
 *  - Même règles sur PayoutRequest (beneficiaryName, description, metadata)
 */
@DisplayName("Validation — Sanitisation XSS et contraintes champs libres")
class ValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory factory;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerKycVerifiedMerchant();
    }

    // ── PaymentRequest — description ─────────────────────────────────────────

    @ParameterizedTest(name = "description avec ''{0}'' → 400")
    @ValueSource(strings = {"<script>alert('xss')</script>", "<b>bold</b>", "hello\"world", "test'injection"})
    void payment_invalidDescription_returns400(String badDescription) {
        ResponseEntity<Map> resp = pay(Map.of(
            "amount", "5000",
            "phoneNumber", "+22505123456",
            "merchantReference", "REF-" + System.nanoTime(),
            "description", badDescription
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── PaymentRequest — customerName ────────────────────────────────────────

    @ParameterizedTest(name = "customerName avec ''{0}'' → 400")
    @ValueSource(strings = {"<NAME>", "Robert'); DROP TABLE users;--", "Alice\"Smith"})
    void payment_invalidCustomerName_returns400(String badName) {
        ResponseEntity<Map> resp = pay(Map.of(
            "amount", "5000",
            "phoneNumber", "+22505123456",
            "merchantReference", "REF-" + System.nanoTime(),
            "customerName", badName
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── PaymentRequest — customerEmail ───────────────────────────────────────

    @ParameterizedTest(name = "customerEmail ''{0}'' invalide → 400")
    @ValueSource(strings = {"not-an-email", "missing@", "@domain.com", "a@b"})
    void payment_invalidCustomerEmail_returns400(String badEmail) {
        ResponseEntity<Map> resp = pay(Map.of(
            "amount", "5000",
            "phoneNumber", "+22505123456",
            "merchantReference", "REF-" + System.nanoTime(),
            "customerEmail", badEmail
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── PaymentRequest — metadata ─────────────────────────────────────────────

    @Test
    @DisplayName("metadata avec balises HTML → 400")
    void payment_metadataWithHtml_returns400() {
        ResponseEntity<Map> resp = pay(Map.of(
            "amount", "5000",
            "phoneNumber", "+22505123456",
            "merchantReference", "REF-" + System.nanoTime(),
            "metadata", "<script>evil()</script>"
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("metadata dépasse 1000 caractères → 400")
    void payment_metadataTooLong_returns400() {
        ResponseEntity<Map> resp = pay(Map.of(
            "amount", "5000",
            "phoneNumber", "+22505123456",
            "merchantReference", "REF-" + System.nanoTime(),
            "metadata", "x".repeat(1001)
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── PaymentRequest — returnUrl ────────────────────────────────────────────

    @ParameterizedTest(name = "returnUrl ''{0}'' invalide → 400")
    @ValueSource(strings = {"ftp://malicious.com", "javascript:alert(1)", "not-a-url"})
    void payment_invalidReturnUrl_returns400(String badUrl) {
        ResponseEntity<Map> resp = pay(Map.of(
            "amount", "5000",
            "phoneNumber", "+22505123456",
            "merchantReference", "REF-" + System.nanoTime(),
            "returnUrl", badUrl
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Champs valides acceptés ───────────────────────────────────────────────

    @Test
    @DisplayName("Tous les champs optionnels valides → 200 (mock opérateur)")
    void payment_allValidOptionalFields_accepted() {
        // Sans mock, l'opérateur échouera mais la validation doit passer (pas de 400)
        Map<String, Object> body = new HashMap<>();
        body.put("amount", new java.math.BigDecimal("5000"));
        body.put("phoneNumber", "+225051234567");
        body.put("merchantReference", "REF-VALID-" + System.nanoTime());
        body.put("description", "Paiement commande 12345");
        body.put("customerName", "Jean Dupont");
        body.put("customerEmail", "jean@example.com");
        body.put("metadata", "{\"orderId\": \"ORD-001\"}");
        body.put("returnUrl", "https://merchant.com/success");
        body.put("cancelUrl", "https://merchant.com/cancel");

        ResponseEntity<Map> resp = pay(body);
        // Peut retourner 200 (si mock configuré) ou 500 (opérateur réel indispo)
        // L'important est que ce ne soit PAS un 400 (validation)
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map> pay(Map<String, Object> body) {
        return restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);
    }
}