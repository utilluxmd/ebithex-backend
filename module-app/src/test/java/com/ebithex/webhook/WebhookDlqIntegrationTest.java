package com.ebithex.webhook;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.webhook.domain.WebhookDelivery;
import com.ebithex.webhook.infrastructure.WebhookDeliveryRepository;
import com.ebithex.webhook.infrastructure.WebhookEndpointRepository;
import com.ebithex.webhook.domain.WebhookEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Dead Letter Queue des webhooks.
 *
 * Couvre :
 *  - Livraison marquée dead-lettered → visible dans GET /internal/webhooks/dead-letters
 *  - Retry manuel DLQ → livraison réinitialisée (deadLettered=false)
 *  - Retry sur une livraison non-DLQ → 500
 *  - SUPPORT peut lister, mais pas relancer (403)
 *  - Livraison non existante → 500 (IllegalArgumentException)
 */
@DisplayName("Webhook DLQ — Dead Letter Queue et retry manuel")
class WebhookDlqIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory           factory;
    @Autowired private WebhookDeliveryRepository deliveryRepository;
    @Autowired private WebhookEndpointRepository endpointRepository;

    private UUID merchantId;
    private UUID endpointId;

    @BeforeEach
    void setUp() {
        TestDataFactory.MerchantCredentials m = factory.registerKycVerifiedMerchant();
        merchantId = m.merchantId();

        WebhookEndpoint endpoint = endpointRepository.save(WebhookEndpoint.builder()
            .merchantId(merchantId)
            .url("https://merchant.test/webhook")
            .signingSecret("test-secret-32bytes-placeholder12")
            .events(Set.of("PAYMENT_SUCCESS"))
            .active(true)
            .build());
        endpointId = endpoint.getId();
    }

    @Test
    @DisplayName("Livraison dead-lettered → visible dans GET /internal/webhooks/dead-letters")
    void deadLettered_visibleInList() throws Exception {
        WebhookDelivery dlq = createDeadLetteredDelivery();

        mockMvc.perform(
            get("/api/internal/webhooks/dead-letters")
                .with(user(factory.adminPrincipal()))
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data").isArray())
         .andExpect(jsonPath("$.data[?(@.id == '" + dlq.getId() + "')]").exists());
    }

    @Test
    @DisplayName("Retry manuel DLQ → livraison réinitialisée (deadLettered=false, attemptCount=0)")
    void retryDeadLetter_resetsDelivery() throws Exception {
        WebhookDelivery dlq = createDeadLetteredDelivery();

        mockMvc.perform(
            post("/api/internal/webhooks/dead-letters/" + dlq.getId() + "/retry")
                .with(user(factory.adminPrincipal()))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk());

        WebhookDelivery reloaded = deliveryRepository.findById(dlq.getId()).orElseThrow();
        assertThat(reloaded.isDeadLettered()).isFalse();
        assertThat(reloaded.getDeadLetteredAt()).isNull();
        assertThat(reloaded.getAttemptCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("SUPPORT peut lister les DLQ")
    void support_canListDlq() throws Exception {
        mockMvc.perform(
            get("/api/internal/webhooks/dead-letters")
                .with(user(factory.supportPrincipal()))
        ).andExpect(status().isOk());
    }

    @Test
    @DisplayName("SUPPORT ne peut pas relancer (403)")
    void support_cannotRetry_forbidden() throws Exception {
        WebhookDelivery dlq = createDeadLetteredDelivery();

        mockMvc.perform(
            post("/api/internal/webhooks/dead-letters/" + dlq.getId() + "/retry")
                .with(user(factory.supportPrincipal()))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Livraison non existante → 500")
    void retryNonExisting_returns500() throws Exception {
        mockMvc.perform(
            post("/api/internal/webhooks/dead-letters/" + UUID.randomUUID() + "/retry")
                .with(user(factory.adminPrincipal()))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().is5xxServerError());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WebhookDelivery createDeadLetteredDelivery() {
        return deliveryRepository.save(WebhookDelivery.builder()
            .endpointId(endpointId)
            .transactionId(UUID.randomUUID())
            .event("PAYMENT_SUCCESS")
            .payload("{\"event\":\"PAYMENT_SUCCESS\"}")
            .attemptCount(5)
            .deadLettered(true)
            .deadLetteredAt(Instant.now())
            .build());
    }
}