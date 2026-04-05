package com.ebithex.shared.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Compteur de rate limiting en Redis via script Lua atomique (fixed-window).
 *
 * <p>Algorithme fixed-window :
 * <ul>
 *   <li>Clé : {@code rl:{identifier}:m:{epoch/60}}  (fenêtre minute)</li>
 *   <li>Clé : {@code rl:{identifier}:h:{epoch/3600}} (fenêtre heure)</li>
 *   <li>INCR atomique + EXPIRE à la première requête de la fenêtre</li>
 *   <li>TTL = durée de la fenêtre → nettoyage automatique</li>
 * </ul>
 *
 * <p><b>Fail-safe :</b> si Redis est indisponible, le fallback {@link LocalRateLimitFallback}
 * prend le relais avec des compteurs en mémoire (par nœud). Ce n'est pas un fail-open pur :
 * le rate limiting reste actif mais avec une précision réduite le temps que Redis revienne.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final StringRedisTemplate     redisTemplate;
    private final LocalRateLimitFallback  localFallback;

    /**
     * Script Lua : INCR atomique + EXPIRE à la création de la clé.
     * Retourne le compteur courant après incrément.
     */
    private static final RedisScript<Long> INCR_SCRIPT = RedisScript.of(
        "local current = redis.call('INCR', KEYS[1])\n" +
        "if current == 1 then\n" +
        "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
        "end\n" +
        "return current",
        Long.class
    );

    /**
     * Vérifie le rate limit pour un identifiant et un plan donnés.
     * Tente Redis en priorité ; bascule sur le fallback local en cas d'erreur.
     */
    public RateLimitResult check(String identifier, RateLimitPlan plan) {
        try {
            return checkRedis(identifier, plan);
        } catch (Exception e) {
            log.warn("Redis indisponible — rate limiting via fallback local pour '{}': {}",
                identifier, e.getMessage());
            return checkLocal(identifier, plan);
        }
    }

    // ── Redis (chemin nominal) ────────────────────────────────────────────────

    private RateLimitResult checkRedis(String identifier, RateLimitPlan plan) {
        long nowSeconds   = System.currentTimeMillis() / 1000;
        long minuteWindow = nowSeconds / plan.minuteWindow;
        long hourWindow   = nowSeconds / plan.hourWindow;

        String minuteKey = "rl:" + identifier + ":m:" + minuteWindow;
        String hourKey   = "rl:" + identifier + ":h:" + hourWindow;

        Long mRaw = redisTemplate.execute(INCR_SCRIPT, List.of(minuteKey), String.valueOf(plan.minuteWindow));
        Long hRaw = redisTemplate.execute(INCR_SCRIPT, List.of(hourKey),   String.valueOf(plan.hourWindow));

        long mCount = mRaw != null ? mRaw : 1;
        long hCount = hRaw != null ? hRaw : 1;

        return buildResult(mCount, hCount, minuteWindow, hourWindow, plan);
    }

    // ── Fallback local (Redis down) ───────────────────────────────────────────

    private RateLimitResult checkLocal(String identifier, RateLimitPlan plan) {
        long nowSeconds   = System.currentTimeMillis() / 1000;
        long minuteWindow = nowSeconds / plan.minuteWindow;
        long hourWindow   = nowSeconds / plan.hourWindow;

        String minuteKey = "rl:" + identifier + ":m:" + minuteWindow;
        String hourKey   = "rl:" + identifier + ":h:" + hourWindow;

        long mCount = localFallback.increment(minuteKey);
        long hCount = localFallback.increment(hourKey);

        return buildResult(mCount, hCount, minuteWindow, hourWindow, plan);
    }

    // ── Logique commune ───────────────────────────────────────────────────────

    private RateLimitResult buildResult(long mCount, long hCount,
                                        long minuteWindow, long hourWindow,
                                        RateLimitPlan plan) {
        boolean minuteExceeded = mCount > plan.minuteLimit;
        boolean hourExceeded   = hCount > plan.hourLimit;

        if (minuteExceeded || hourExceeded) {
            int  limit   = minuteExceeded ? plan.minuteLimit : plan.hourLimit;
            long resetAt = minuteExceeded
                ? (minuteWindow + 1) * plan.minuteWindow
                : (hourWindow   + 1) * plan.hourWindow;
            return RateLimitResult.exceeded(limit, resetAt);
        }

        int  remaining = (int) Math.min(plan.minuteLimit - mCount, plan.hourLimit - hCount);
        long resetAt   = (minuteWindow + 1) * plan.minuteWindow;
        return RateLimitResult.allowed(plan.minuteLimit, remaining, resetAt);
    }

    // ── Value type ────────────────────────────────────────────────────────────

    public record RateLimitResult(
        boolean allowed,
        int     limit,
        int     remaining,
        long    resetAt   // Unix timestamp (secondes)
    ) {
        public static RateLimitResult allowed(int limit, int remaining, long resetAt) {
            return new RateLimitResult(true, limit, remaining, resetAt);
        }
        public static RateLimitResult exceeded(int limit, long resetAt) {
            return new RateLimitResult(false, limit, 0, resetAt);
        }
    }
}