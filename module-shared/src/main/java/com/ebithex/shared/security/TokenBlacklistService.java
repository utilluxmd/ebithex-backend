package com.ebithex.shared.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;

/**
 * Blacklist Redis pour les JTI révoqués (logout + rotation refresh token).
 *
 * Clé Redis : jwt:revoked:{jti}   TTL = durée restante du token
 *
 * Fail-open : si Redis est indisponible, isRevoked() retourne false
 * (on ne bloque pas les paiements pour un service tiers dégradé).
 * Corollaire : en cas de panne Redis, les tokens révoqués redeviennent
 * temporairement valides jusqu'au rétablissement — risque accepté.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "jwt:revoked:";

    private final RedisTemplate<String, String> redisTemplate;

    /** Révoque un token — clé TTL-ée jusqu'à l'expiration naturelle du token. */
    public void revoke(String jti, Date expiration) {
        long ttlMs = expiration.getTime() - System.currentTimeMillis();
        if (ttlMs <= 0) {
            return; // Token déjà expiré — inutile de le stocker
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMs));
            log.debug("Token révoqué: jti={} ttl={}ms", jti, ttlMs);
        } catch (Exception e) {
            log.warn("Redis indisponible — jti {} NON révoqué dans la blacklist", jti, e);
        }
    }

    /** Retourne true si le JTI est dans la blacklist. Fail-open si Redis est down. */
    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("Redis indisponible — vérification blacklist échouée, fail-open pour jti: {}", jti);
            return false;
        }
    }
}