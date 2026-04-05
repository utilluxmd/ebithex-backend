package com.ebithex.shared.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fallback en mémoire pour le rate limiting lorsque Redis est indisponible.
 *
 * <p><b>Fonctionnement :</b> compteurs {@code AtomicLong} par clé de fenêtre temporelle.
 * La clé encode déjà la fenêtre courante (ex: {@code rl:merchant:xxx:m:29265440}),
 * donc chaque nouvelle fenêtre crée automatiquement de nouvelles entrées.
 *
 * <p><b>Limitations intentionnelles :</b>
 * <ul>
 *   <li>Comptage par nœud uniquement (pas distribué) — acceptable en fallback</li>
 *   <li>Légèrement moins précis que Redis — acceptable car Redis est la source de vérité</li>
 *   <li>Résistant aux fuites mémoire via nettoyage planifié des fenêtres expirées</li>
 * </ul>
 *
 * <p><b>Sécurité :</b> empêche le contournement total du rate limiting même si Redis
 * est down (contrairement au fail-open pur qui ne bloque rien).
 */
@Component
@Slf4j
public class LocalRateLimitFallback {

    // Clé → compteur atomique
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * Incrémente le compteur pour la clé donnée et retourne la valeur après incrément.
     */
    public long increment(String key) {
        return counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Supprime les compteurs dont la clé correspond à des fenêtres temporelles expirées.
     * Exécuté toutes les 2 minutes pour éviter les fuites mémoire.
     *
     * <p>Format des clés : {@code rl:{id}:m:{minuteWindow}} ou {@code rl:{id}:h:{hourWindow}}
     * où {@code minuteWindow = epoch/60} et {@code hourWindow = epoch/3600}.
     */
    @Scheduled(fixedDelay = 120_000)
    public void evictExpiredCounters() {
        long nowSeconds         = System.currentTimeMillis() / 1000;
        long currentMinuteWin   = nowSeconds / 60;
        long currentHourWin     = nowSeconds / 3600;

        int removed = 0;
        for (String key : counters.keySet()) {
            try {
                int mIdx = key.lastIndexOf(":m:");
                int hIdx = key.lastIndexOf(":h:");
                if (mIdx >= 0) {
                    long keyWin = Long.parseLong(key.substring(mIdx + 3));
                    if (keyWin < currentMinuteWin - 1) { counters.remove(key); removed++; }
                } else if (hIdx >= 0) {
                    long keyWin = Long.parseLong(key.substring(hIdx + 3));
                    if (keyWin < currentHourWin - 1) { counters.remove(key); removed++; }
                }
            } catch (NumberFormatException ignored) {
                // Clé de format inattendu — on la conserve
            }
        }
        if (removed > 0) {
            log.debug("LocalRateLimitFallback: {} compteur(s) de fenêtres expirées supprimé(s) ({} restants)",
                removed, counters.size());
        }
    }

    /** Taille courante de la map (utile pour les métriques / health checks). */
    public int size() {
        return counters.size();
    }
}
