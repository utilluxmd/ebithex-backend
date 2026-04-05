package com.ebithex.shared.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Protection brute-force sur le login marchand.
 *
 * Stratégie :
 *  - Chaque échec incrémente un compteur Redis : login:attempts:{email}
 *  - À la Nème tentative → clé login:locked:{email} posée pour LOCKOUT_DURATION
 *  - La vérification du verrou est faite AVANT la validation du mot de passe
 *    (prévient les attaques d'énumération sur le timing)
 *
 * Fail-open : si Redis est indisponible on ne bloque pas le login légitime.
 * Corollaire : la protection est temporairement inactive si Redis tombe.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String ATTEMPTS_PREFIX = "login:attempts:";
    private static final String LOCKED_PREFIX   = "login:locked:";

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${ebithex.security.login.max-attempts:5}")
    private int maxAttempts;

    @Value("${ebithex.security.login.lockout-duration-minutes:15}")
    private long lockoutDurationMinutes;

    /**
     * Doit être appelé AVANT toute vérification de mot de passe.
     * Lance une EbithexException si le compte est temporairement verrouillé.
     */
    public void checkNotLocked(String email) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(LOCKED_PREFIX + email))) {
                log.warn("Tentative de login bloquée (brute-force lock) pour: {}", email);
                throw new com.ebithex.shared.exception.EbithexException(
                    com.ebithex.shared.exception.ErrorCode.LOGIN_ATTEMPTS_EXCEEDED,
                    "Trop de tentatives de connexion échouées. Réessayez dans " + lockoutDurationMinutes + " minutes.");
            }
        } catch (com.ebithex.shared.exception.EbithexException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis indisponible — vérification brute-force ignorée (fail-open) pour: {}", email, e);
        }
    }

    /** Appelé sur chaque échec d'authentification. */
    public void recordFailure(String email) {
        try {
            String attemptsKey = ATTEMPTS_PREFIX + email;
            Long count = redisTemplate.opsForValue().increment(attemptsKey);

            // Fixer le TTL sur le premier échec (fenêtre glissante)
            if (count != null && count == 1) {
                redisTemplate.expire(attemptsKey, Duration.ofMinutes(lockoutDurationMinutes));
            }

            if (count != null && count >= maxAttempts) {
                redisTemplate.opsForValue().set(
                    LOCKED_PREFIX + email, "1",
                    Duration.ofMinutes(lockoutDurationMinutes));
                redisTemplate.delete(attemptsKey);
                log.warn("Compte verrouillé après {} tentatives échouées: {}", maxAttempts, email);
            } else {
                log.debug("Échec de login {}/{} pour: {}", count, maxAttempts, email);
            }
        } catch (Exception e) {
            log.warn("Redis indisponible — enregistrement d'échec ignoré pour: {}", email, e);
        }
    }

    /** Appelé sur un login réussi — réinitialise le compteur. */
    public void recordSuccess(String email) {
        try {
            redisTemplate.delete(ATTEMPTS_PREFIX + email);
        } catch (Exception e) {
            log.warn("Redis indisponible — réinitialisation du compteur ignorée pour: {}", email, e);
        }
    }
}