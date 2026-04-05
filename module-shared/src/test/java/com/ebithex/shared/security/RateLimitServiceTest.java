package com.ebithex.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour RateLimitService.
 * Couvre le chemin Redis nominal, le fallback local en cas d'erreur Redis,
 * et la logique de dépassement de limite (minute et heure).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService — rate limiting Redis + fallback local")
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private LocalRateLimitFallback localFallback;
    private RateLimitService       service;

    @BeforeEach
    void setUp() {
        localFallback = new LocalRateLimitFallback();
        service       = new RateLimitService(redisTemplate, localFallback);
    }

    // ── Chemin Redis nominal ──────────────────────────────────────────────────

    @Test
    @DisplayName("check() — sous les limites → allowed=true, remaining correct")
    void check_underLimits_returnsAllowed() {
        // 5 req/min, 10 req/h — bien en dessous des limites ANONYMOUS (10/min, 300/h)
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenReturn(5L)   // minuteCount
            .thenReturn(10L); // hourCount

        RateLimitService.RateLimitResult result = service.check("user:123", RateLimitPlan.ANONYMOUS);

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(10); // minuteLimit ANONYMOUS
        assertThat(result.remaining()).isEqualTo(5); // min(10-5, 300-10) = 5
        assertThat(result.resetAt()).isPositive();
    }

    @Test
    @DisplayName("check() — dépassement limite minute → allowed=false")
    void check_minuteLimitExceeded_returnsExceeded() {
        // 11 req cette minute, limite = 10 pour ANONYMOUS
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenReturn(11L) // minuteCount > 10
            .thenReturn(5L); // hourCount OK

        RateLimitService.RateLimitResult result = service.check("user:123", RateLimitPlan.ANONYMOUS);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("check() — dépassement limite heure → allowed=false")
    void check_hourLimitExceeded_returnsExceeded() {
        // 1 req cette minute (OK), mais 301 req cette heure (limite = 300 ANONYMOUS)
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenReturn(1L)    // minuteCount OK
            .thenReturn(301L); // hourCount > 300

        RateLimitService.RateLimitResult result = service.check("user:123", RateLimitPlan.ANONYMOUS);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.limit()).isEqualTo(300);
    }

    @Test
    @DisplayName("check() — null Redis response → traité comme 1 (INCR échoue → aucun blocage)")
    void check_nullRedisResponse_treatedAsOne() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenReturn(null)  // null → 1 (défaut)
            .thenReturn(null);

        RateLimitService.RateLimitResult result = service.check("user:123", RateLimitPlan.ANONYMOUS);

        assertThat(result.allowed()).isTrue();
    }

    // ── Fallback local (Redis down) ───────────────────────────────────────────

    @Test
    @DisplayName("check() — Redis lance exception → bascule sur fallback local")
    void check_redisException_fallsBackToLocal() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenThrow(new RuntimeException("Redis connection refused"));

        // Premier appel : devrait utiliser le fallback local (count=1 → allowed)
        RateLimitService.RateLimitResult result = service.check("user:123", RateLimitPlan.ANONYMOUS);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isPositive();
    }

    @Test
    @DisplayName("check() fallback — dépasse la limite minute localement → allowed=false")
    void check_fallback_minuteLimitExceeded() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenThrow(new RuntimeException("Redis down"));

        // ANONYMOUS : 10 req/min — on appelle 11 fois
        RateLimitService.RateLimitResult result = null;
        for (int i = 0; i < 11; i++) {
            result = service.check("ip:10.0.0.1", RateLimitPlan.ANONYMOUS);
        }

        assertThat(result).isNotNull();
        assertThat(result.allowed()).isFalse();
    }

    // ── Plans différents ──────────────────────────────────────────────────────

    @Test
    @DisplayName("check() — plan PREMIUM a des limites plus élevées que ANONYMOUS")
    void check_premiumPlan_hasHigherLimits() {
        // 150 req cette minute : dépasserait ANONYMOUS (10) mais pas PREMIUM (300)
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenReturn(150L)    // minuteCount
            .thenReturn(1000L);  // hourCount

        RateLimitService.RateLimitResult result = service.check("merchant:premium", RateLimitPlan.PREMIUM);

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(300); // PREMIUM minuteLimit
    }

    // ── resetAt cohérence ────────────────────────────────────────────────────

    @Test
    @DisplayName("check() allowed — resetAt est dans le futur proche (< 60s)")
    void check_allowed_resetAtIsNearFuture() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenReturn(1L)
            .thenReturn(1L);

        long before = System.currentTimeMillis() / 1000;
        RateLimitService.RateLimitResult result = service.check("user:1", RateLimitPlan.STANDARD);
        long after  = System.currentTimeMillis() / 1000;

        // resetAt doit être dans [now, now + 60s]
        assertThat(result.resetAt()).isGreaterThanOrEqualTo(before);
        assertThat(result.resetAt()).isLessThanOrEqualTo(after + 60);
    }
}
