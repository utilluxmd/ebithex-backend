package com.ebithex.operator.outbound;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache en mémoire des tokens OAuth2 opérateurs.
 *
 * Évite de demander un nouveau token à chaque appel (rate limit opérateur).
 * Renouvèle automatiquement 30 secondes avant expiration (marge de sécurité).
 *
 * Thread-safe via ConcurrentHashMap.
 */
@Component
@Slf4j
public class OperatorTokenCache {

    private record CachedToken(String value, Instant expiresAt) {
        /** Valide si au moins 30 secondes restent avant expiration. */
        boolean isValid() {
            return Instant.now().isBefore(expiresAt.minusSeconds(30));
        }
    }

    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    /**
     * Retourne un token valide pour la clé donnée, ou null si absent/expiré.
     */
    public String get(String key) {
        CachedToken cached = cache.get(key);
        if (cached != null && cached.isValid()) {
            return cached.value();
        }
        cache.remove(key);
        return null;
    }

    /**
     * Stocke un token avec sa durée de vie en secondes.
     */
    public void put(String key, String token, long expiresInSeconds) {
        cache.put(key, new CachedToken(token, Instant.now().plusSeconds(expiresInSeconds)));
        log.debug("Token mis en cache pour: {} (expire dans {}s)", key, expiresInSeconds);
    }

    public void invalidate(String key) {
        cache.remove(key);
    }
}
