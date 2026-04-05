package com.ebithex.security;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.shared.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests d'intégration — Rate limiting par marchand.
 *
 * Couvre :
 *  - Requêtes sous le plafond → 200 + headers X-RateLimit-*
 *  - Requêtes au-delà du plafond → 429 + Retry-After
 *  - Fail-open : Redis indisponible → requête passe quand même
 *  - Callbacks exclus du rate limiting
 *  - Headers X-RateLimit présents sur chaque réponse
 */
@DisplayName("Rate Limiting — Plafond par marchand, fail-open Redis")
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory    factory;
    @Autowired private StringRedisTemplate redisTemplate;
    @SpyBean   private RateLimitService   rateLimitService;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerKycVerifiedMerchant();

        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-" + System.nanoTime(), null, "En attente"));

        // Purger les clés Redis de rate limit pour ce marchand avant chaque test
        String pattern = "rl:merchant:" + merchant.merchantId() + ":*";
        redisTemplate.keys(pattern).forEach(redisTemplate::delete);
    }

    @Test
    @DisplayName("Headers X-RateLimit présents sur chaque réponse")
    void rateLimitHeaders_presentOnResponse() {
        ResponseEntity<Map> resp = makePaymentRequest();

        assertThat(resp.getHeaders().getFirst("X-RateLimit-Limit")).isNotNull();
        assertThat(resp.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
        assertThat(resp.getHeaders().getFirst("X-RateLimit-Reset")).isNotNull();
    }

    @Test
    @DisplayName("Requête normale → 200, Remaining décrémenté")
    void normalRequest_rateLimitDecremented() {
        ResponseEntity<Map> resp1 = makePaymentRequest();
        ResponseEntity<Map> resp2 = makePaymentRequest();

        int remaining1 = Integer.parseInt(resp1.getHeaders().getFirst("X-RateLimit-Remaining"));
        int remaining2 = Integer.parseInt(resp2.getHeaders().getFirst("X-RateLimit-Remaining"));

        assertThat(remaining2).isLessThan(remaining1);
    }

    @Test
    @DisplayName("Dépassement plafond minute → 429 avec Retry-After")
    void exceedMinuteLimit_returns429() {
        // Simuler que le check retourne "exceeded"
        doReturn(RateLimitService.RateLimitResult.exceeded(10,
            System.currentTimeMillis() / 1000 + 60))
            .when(rateLimitService).check(any(), any());

        ResponseEntity<Map> resp = makePaymentRequest();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(resp.getBody().get("error")).isEqualTo("Too Many Requests");
    }

    @Test
    @DisplayName("Fail-open : Redis indisponible → requête passe (200)")
    void redisUnavailable_failOpen() {
        // Simuler une exception Redis dans RateLimitService
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("Redis down"))
            .when(rateLimitService).check(any(), any());

        ResponseEntity<Map> resp = makePaymentRequest();

        // Ne doit pas retourner 429 — fail-open
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Endpoint callback exclus du rate limiting")
    void callbackEndpoint_notRateLimited() {
        // Forcer exceeded sur tous les checks
        doReturn(RateLimitService.RateLimitResult.exceeded(0,
            System.currentTimeMillis() / 1000 + 60))
            .when(rateLimitService).check(any(), any());

        // Le callback ne doit pas être intercepté par le filtre
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/callbacks/mtn/notify"), HttpMethod.POST,
            new HttpEntity<>(Map.of("reference", "OP-999"), new HttpHeaders()),
            Map.class);

        // Peu importe le résultat métier, ça ne doit pas être un 429
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map> makePaymentRequest() {
        Map<String, Object> body = Map.of(
            "amount",            "1000",
            "phoneNumber",       "+22505" + (System.nanoTime() % 1000000),
            "merchantReference", "RL-" + UUID.randomUUID()
        );
        return restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);
    }
}