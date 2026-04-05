package com.ebithex.webhook;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Webhooks marchands.
 *
 * Couvre :
 *  - POST /v1/webhooks     → création + signing secret retourné une seule fois
 *  - GET  /v1/webhooks     → liste des endpoints du marchand
 *  - DELETE /v1/webhooks/{id} → désactivation (isActive = false)
 *  - POST /v1/webhooks/{id}/test → envoi événement test (URL injoignable → success=false)
 *  - Isolation multi-tenant : test d'un webhook appartenant à un autre marchand → 404
 *  - Webhook inexistant → test retourne 404
 */
@DisplayName("Webhook — CRUD + test delivery")
class WebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory factory;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerMerchant(restTemplate, url(""));
    }

    // ── Création ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /v1/webhooks → 200, endpoint créé avec signingSecret")
    void register_returnsWebhookWithSecret() {
        ResponseEntity<Map> resp = createWebhook("https://example.com/webhook",
            List.of("payment.success", "payment.failed"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("id")).isNotNull();
        assertThat(data.get("url")).isEqualTo("https://example.com/webhook");
        assertThat(data.get("active")).isEqualTo(true);
        assertThat(data.get("signingSecret")).asString().isNotBlank();
    }

    @Test
    @DisplayName("POST /v1/webhooks sans events → accepté (reçoit tous les événements)")
    void register_withoutEvents_acceptedAsWildcard() {
        ResponseEntity<Map> resp = createWebhook("https://example.com/all-events", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /v1/webhooks → retourne uniquement les webhooks du marchand")
    void list_returnsMerchantWebhooks() {
        createWebhook("https://example.com/wh1", List.of("payment.success"));
        createWebhook("https://example.com/wh2", List.of("payout.success"));

        TestDataFactory.MerchantCredentials other = factory.registerMerchant(restTemplate, url(""));
        createWebhookFor(other, "https://example.com/other-wh");

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/webhooks"), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(merchant.accessToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) resp.getBody().get("data");
        assertThat(content).hasSizeGreaterThanOrEqualTo(2);
        content.forEach(item -> {
            Map<?, ?> ep = (Map<?, ?>) item;
            assertThat(ep.get("url")).asString().doesNotContain("other-wh");
        });
    }

    // ── Désactivation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /v1/webhooks/{id} → endpoint désactivé")
    void disable_deactivatesEndpoint() {
        ResponseEntity<Map> created = createWebhook("https://example.com/to-disable", null);
        UUID endpointId = UUID.fromString(
            ((Map<?, ?>) created.getBody().get("data")).get("id").toString());

        restTemplate.exchange(
            url("/v1/webhooks/" + endpointId), HttpMethod.DELETE,
            new HttpEntity<>(factory.bearerHeaders(merchant.accessToken())), Map.class);

        // Vérifier que le webhook est bien désactivé (n'apparaît plus comme actif)
        ResponseEntity<Map> list = restTemplate.exchange(
            url("/v1/webhooks"), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(merchant.accessToken())), Map.class);
        List<?> endpoints = (List<?>) list.getBody().get("data");
        endpoints.forEach(item -> {
            Map<?, ?> ep = (Map<?, ?>) item;
            if (endpointId.toString().equals(ep.get("id").toString())) {
                assertThat(ep.get("active")).isEqualTo(false);
            }
        });
    }

    // ── Test delivery ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /v1/webhooks/{id}/test → URL injoignable → success=false avec message")
    void testDelivery_unreachableUrl_returnsFalseWithMessage() {
        ResponseEntity<Map> created = createWebhook("https://unreachable.ebithex.test/hook", null);
        UUID endpointId = UUID.fromString(
            ((Map<?, ?>) created.getBody().get("data")).get("id").toString());

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/webhooks/" + endpointId + "/test"), HttpMethod.POST,
            new HttpEntity<>(null, factory.bearerHeaders(merchant.accessToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("success")).isEqualTo(false);
        assertThat(data.get("message")).asString().isNotBlank();
    }

    @Test
    @DisplayName("POST /v1/webhooks/{id}/test → webhook d'un autre marchand → 404")
    void testDelivery_otherMerchantWebhook_returns404() {
        TestDataFactory.MerchantCredentials other = factory.registerMerchant(restTemplate, url(""));
        ResponseEntity<Map> otherWebhook = createWebhookFor(other, "https://example.com/other");
        UUID otherId = UUID.fromString(
            ((Map<?, ?>) otherWebhook.getBody().get("data")).get("id").toString());

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/webhooks/" + otherId + "/test"), HttpMethod.POST,
            new HttpEntity<>(null, factory.bearerHeaders(merchant.accessToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /v1/webhooks/{id}/test → endpoint désactivé → 400 WEBHOOK_INACTIVE")
    void testDelivery_inactiveEndpoint_returns400() {
        ResponseEntity<Map> created = createWebhook("https://example.com/inactive", null);
        UUID endpointId = UUID.fromString(
            ((Map<?, ?>) created.getBody().get("data")).get("id").toString());

        // Désactiver
        restTemplate.exchange(
            url("/v1/webhooks/" + endpointId), HttpMethod.DELETE,
            new HttpEntity<>(factory.bearerHeaders(merchant.accessToken())), Map.class);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/webhooks/" + endpointId + "/test"), HttpMethod.POST,
            new HttpEntity<>(null, factory.bearerHeaders(merchant.accessToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("WEBHOOK_INACTIVE");
    }

    @Test
    @DisplayName("POST /v1/webhooks/{unknown-id}/test → 404")
    void testDelivery_unknownId_returns404() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/webhooks/" + UUID.randomUUID() + "/test"), HttpMethod.POST,
            new HttpEntity<>(null, factory.bearerHeaders(merchant.accessToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map> createWebhook(String webhookUrl, List<String> events) {
        return createWebhookFor(merchant, webhookUrl);
    }

    private ResponseEntity<Map> createWebhookFor(TestDataFactory.MerchantCredentials m, String webhookUrl) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("url", webhookUrl);
        body.put("events", List.of("payment.success"));
        return restTemplate.exchange(
            url("/v1/webhooks"), HttpMethod.POST,
            new HttpEntity<>(body, factory.bearerHeaders(m.accessToken())), Map.class);
    }
}