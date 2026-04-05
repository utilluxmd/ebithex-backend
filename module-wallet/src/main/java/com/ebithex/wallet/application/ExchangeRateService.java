package com.ebithex.wallet.application;

import com.ebithex.shared.domain.Currency;
import com.ebithex.wallet.infrastructure.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service de taux de change entre devises africaines et internationales.
 *
 * Stratégie de cache à deux niveaux :
 *  1. Cache en mémoire (ConcurrentHashMap) — TTL configurable (défaut 1h)
 *     → Performances maximales, pas de round-trip DB
 *  2. Cache DB (table exchange_rates) — persisté, survit aux redémarrages
 *     → Fallback si l'API externe est indisponible
 *
 * Fournisseurs supportés :
 *  - openexchangerates : openexchangerates.org (gratuit jusqu'à 1000 req/mois)
 *  - manual : taux saisis manuellement en DB (sans refresh automatique)
 *
 * Refresh automatique : toutes les heures via @Scheduled.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final WebClient.Builder webClientBuilder;

    @Value("${ebithex.exchange-rate.provider:manual}")
    private String provider;

    @Value("${ebithex.exchange-rate.api-key:}")
    private String apiKey;

    @Value("${ebithex.exchange-rate.base-currency:USD}")
    private String baseCurrency;

    @Value("${ebithex.exchange-rate.cache-ttl-seconds:3600}")
    private long cacheTtlSeconds;

    /** Cache en mémoire : clé = "FROM_TO", valeur = [rate, expiresAt (epoch ms)] */
    private final ConcurrentHashMap<String, long[]> rateTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> rateCache   = new ConcurrentHashMap<>();

    /**
     * Convertit un montant d'une devise vers une autre.
     *
     * @throws IllegalArgumentException si le taux n'est pas disponible
     */
    public BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        if (from == to) return amount;
        BigDecimal rate = getRate(from, to);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Retourne le taux de change de {@code from} vers {@code to}.
     * Cherche dans l'ordre : cache mémoire → cache DB → API externe → exception.
     */
    @Transactional(readOnly = true)
    public BigDecimal getRate(Currency from, Currency to) {
        if (from == to) return BigDecimal.ONE;

        String key = from.name() + "_" + to.name();

        // 1. Cache mémoire (le plus rapide)
        if (isCacheValid(key)) {
            return rateCache.get(key);
        }

        // 2. Cache DB (survit aux redémarrages)
        return exchangeRateRepository.findByFromCurrencyAndToCurrency(from, to)
            .map(er -> {
                putInMemoryCache(key, er.getRate());
                return er.getRate();
            })
            .orElseGet(() -> {
                // 3. Tentative de cross-rate via USD
                return crossRateViaUsd(from, to);
            });
    }

    /**
     * Calcule un cross-rate en passant par USD.
     * Exemple : XOF → NGN = (XOF→USD) × (USD→NGN)
     */
    private BigDecimal crossRateViaUsd(Currency from, Currency to) {
        Currency usd = Currency.USD;
        if (from == usd) {
            return getDirectRate(usd, to);
        }
        if (to == usd) {
            return getDirectRate(from, usd);
        }
        BigDecimal fromToUsd = getDirectRate(from, usd);
        BigDecimal usdToTarget = getDirectRate(usd, to);
        BigDecimal cross = fromToUsd.multiply(usdToTarget).setScale(8, RoundingMode.HALF_UP);
        // Cache le cross-rate calculé
        String key = from.name() + "_" + to.name();
        putInMemoryCache(key, cross);
        return cross;
    }

    private BigDecimal getDirectRate(Currency from, Currency to) {
        return exchangeRateRepository.findByFromCurrencyAndToCurrency(from, to)
            .map(er -> er.getRate())
            .orElseThrow(() -> new IllegalArgumentException(
                "Exchange rate not available: " + from + " → " + to
                + ". Run /admin/exchange-rates/refresh or configure manually."));
    }

    // ─── Refresh automatique ──────────────────────────────────────────────────

    /**
     * Rafraîchit les taux depuis le fournisseur configuré.
     * Planifié toutes les heures (configurable via cron).
     * Appelable aussi manuellement via l'API d'administration.
     */
    @Scheduled(fixedDelayString = "${ebithex.exchange-rate.cache-ttl-seconds:3600}000")
    @Transactional
    public void refreshRates() {
        if ("manual".equalsIgnoreCase(provider)) {
            log.debug("Exchange rate provider = manual — skipping auto-refresh");
            return;
        }
        if ("openexchangerates".equalsIgnoreCase(provider)) {
            refreshFromOpenExchangeRates();
        } else {
            log.warn("Unknown exchange rate provider: {} — skipping refresh", provider);
        }
    }

    @SuppressWarnings("unchecked")
    private void refreshFromOpenExchangeRates() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenExchangeRates API key not configured — skipping refresh");
            return;
        }

        try {
            WebClient client = webClientBuilder
                .baseUrl("https://openexchangerates.org")
                .build();

            Map<String, Object> response = client.get()
                .uri("/api/latest.json?app_id={key}&base={base}", apiKey, baseCurrency)
                .retrieve().bodyToMono(Map.class).block();

            if (response == null || !response.containsKey("rates")) {
                log.error("OpenExchangeRates: unexpected response format");
                return;
            }

            Map<String, Number> rates = (Map<String, Number>) response.get("rates");
            Currency base = Currency.valueOf(baseCurrency);
            int updated = 0;

            for (Currency target : Currency.values()) {
                if (target == base) continue;
                Number rateValue = rates.get(target.name());
                if (rateValue == null) continue;

                BigDecimal rate = new BigDecimal(rateValue.toString()).setScale(8, RoundingMode.HALF_UP);
                exchangeRateRepository.upsert(base.name(), target.name(), rate, "openexchangerates");

                // Invalider le cache mémoire pour forcer le rechargement
                rateTimestamps.remove(base.name() + "_" + target.name());
                updated++;
            }

            log.info("Exchange rates refreshed from OpenExchangeRates — {} currencies updated", updated);
            rateCache.clear();
            rateTimestamps.clear();

        } catch (Exception e) {
            log.error("Failed to refresh exchange rates from OpenExchangeRates: {}", e.getMessage());
        }
    }

    // ─── Cache mémoire ────────────────────────────────────────────────────────

    private boolean isCacheValid(String key) {
        long[] meta = rateTimestamps.get(key);
        if (meta == null) return false;
        return System.currentTimeMillis() < meta[0];
    }

    private void putInMemoryCache(String key, BigDecimal rate) {
        rateCache.put(key, rate);
        rateTimestamps.put(key, new long[]{System.currentTimeMillis() + cacheTtlSeconds * 1000});
    }
}