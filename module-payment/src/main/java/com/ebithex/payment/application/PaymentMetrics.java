package com.ebithex.payment.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.ebithex.shared.domain.OperatorType;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Métriques Prometheus exposées sous /actuator/prometheus.
 *
 * Compteurs :
 *   ebithex_payments_initiated_total{operator}
 *   ebithex_payments_success_total{operator}
 *   ebithex_payments_failed_total{operator}
 *   ebithex_payouts_initiated_total{operator}
 *   ebithex_payouts_success_total{operator}
 *   ebithex_payouts_failed_total{operator}
 *
 * Timers :
 *   ebithex_operator_call_duration_seconds{operator, operation}
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    // Cache des compteurs pour éviter de les recréer à chaque appel
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer>   timers   = new ConcurrentHashMap<>();

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Paiements ─────────────────────────────────────────────────────────────

    public void paymentInitiated(OperatorType operator) {
        counter("ebithex.payments.initiated", operator).increment();
    }

    public void paymentSuccess(OperatorType operator) {
        counter("ebithex.payments.success", operator).increment();
    }

    public void paymentFailed(OperatorType operator) {
        counter("ebithex.payments.failed", operator).increment();
    }

    // ── Payouts ───────────────────────────────────────────────────────────────

    public void payoutInitiated(OperatorType operator) {
        counter("ebithex.payouts.initiated", operator).increment();
    }

    public void payoutSuccess(OperatorType operator) {
        counter("ebithex.payouts.success", operator).increment();
    }

    public void payoutFailed(OperatorType operator) {
        counter("ebithex.payouts.failed", operator).increment();
    }

    // ── Durée appels opérateur ────────────────────────────────────────────────

    public Timer.Sample startOperatorCallTimer() {
        return Timer.start(registry);
    }

    public void stopOperatorCallTimer(Timer.Sample sample, OperatorType operator, String operation) {
        String key = "ebithex.operator.call.duration|" + operator.name() + "|" + operation;
        Timer t = timers.computeIfAbsent(key, k ->
            Timer.builder("ebithex.operator.call.duration")
                .description("Durée des appels vers l'opérateur Mobile Money")
                .tag("operator",  operator.name())
                .tag("operation", operation)
                .register(registry));
        sample.stop(t);
    }

    // ── AML ───────────────────────────────────────────────────────────────────

    public void amlAlertCreated(String ruleCode, String severity) {
        Counter.builder("ebithex.aml.alerts.created")
            .description("Nombre d'alertes AML créées")
            .tag("rule",     ruleCode)
            .tag("severity", severity)
            .register(registry)
            .increment();
    }

    public void disputeOpened() {
        Counter.builder("ebithex.disputes.opened")
            .description("Nombre de litiges ouverts")
            .register(registry)
            .increment();
    }

    public void disputeResolved(String resolution) {
        Counter.builder("ebithex.disputes.resolved")
            .description("Nombre de litiges résolus")
            .tag("resolution", resolution)
            .register(registry)
            .increment();
    }

    public void settlementBatchCreated(String operator) {
        Counter.builder("ebithex.settlement.batches.created")
            .description("Nombre de batches de règlement créés")
            .tag("operator", operator)
            .register(registry)
            .increment();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Counter counter(String name, OperatorType operator) {
        String key = name + "|" + operator.name();
        return counters.computeIfAbsent(key, k ->
            Counter.builder(name)
                .description("Nombre de " + name.replace("ebithex.", "").replace(".", " "))
                .tag("operator", operator.name())
                .register(registry));
    }
}