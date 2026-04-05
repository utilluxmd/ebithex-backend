package com.ebithex.payment.health;

import com.ebithex.operatorfloat.application.OperatorFloatService;
import com.ebithex.operatorfloat.domain.OperatorFloat;
import com.ebithex.shared.domain.OperatorType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indicateur de santé custom exposé via /actuator/health.
 *
 * Agrège deux sources de données :
 *   1. État des circuit breakers Resilience4j (operator-payment, operator-disbursement)
 *   2. Niveaux de float par opérateur (alerte si en dessous du seuil)
 *
 * Retourne DOWN si un circuit breaker est OPEN, ou si tous les floats sont à zéro.
 * Retourne WARNING (statut custom) si un float est bas mais les circuits sont fermés.
 */
@Component
@RequiredArgsConstructor
public class OperatorHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final OperatorFloatService   floatService;

    private static final List<String> CB_NAMES = List.of(
        "operator-payment",
        "operator-disbursement"
    );

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean hasOpenCircuit   = false;
        boolean hasLowFloat      = false;

        // ── Circuit breakers ───────────────────────────────────────────────
        Map<String, String> circuitBreakers = new LinkedHashMap<>();
        for (String name : CB_NAMES) {
            try {
                CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
                CircuitBreaker.State state = cb.getState();
                circuitBreakers.put(name, state.name());
                if (state == CircuitBreaker.State.OPEN) {
                    hasOpenCircuit = true;
                }
            } catch (Exception e) {
                circuitBreakers.put(name, "UNKNOWN");
            }
        }
        details.put("circuitBreakers", circuitBreakers);

        // ── Float levels ───────────────────────────────────────────────────
        Map<String, Object> floats = new LinkedHashMap<>();
        try {
            List<OperatorFloat> allFloats = floatService.getAllFloats();
            for (OperatorFloat f : allFloats) {
                boolean isLow = f.getBalance().compareTo(f.getLowBalanceThreshold()) < 0;
                if (isLow) hasLowFloat = true;
                floats.put(f.getOperatorType().name(), Map.of(
                    "balance",   f.getBalance(),
                    "threshold", f.getLowBalanceThreshold(),
                    "status",    isLow ? "LOW" : "OK"
                ));
            }
            // Ajouter les opérateurs non encore initialisés
            for (OperatorType op : OperatorType.values()) {
                if (op == OperatorType.AUTO) continue;
                floats.putIfAbsent(op.name(), Map.of("status", "NOT_INITIALIZED"));
            }
        } catch (Exception e) {
            details.put("floatError", e.getMessage());
        }
        details.put("floats", floats);

        if (hasOpenCircuit) {
            return Health.down()
                .withDetails(details)
                .withDetail("reason", "Un ou plusieurs circuit breakers opérateur sont OPEN")
                .build();
        }
        if (hasLowFloat) {
            return Health.status("WARNING")
                .withDetails(details)
                .withDetail("reason", "Float bas sur un ou plusieurs opérateurs")
                .build();
        }
        return Health.up().withDetails(details).build();
    }
}