package com.ebithex.payment.application;

import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.operator.outbound.MobileMoneyOperator.OperatorRefundResult;
import com.ebithex.operator.outbound.OperatorDisbursementRequest;
import com.ebithex.operator.outbound.OperatorPaymentRequest;
import com.ebithex.operator.outbound.OperatorRegistry;

import java.math.BigDecimal;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Passerelle vers les adaptateurs opérateurs Mobile Money.
 *
 * Rôle : point d'entrée unique, Spring-managed, qui applique les circuit breakers
 * Resilience4j sur tous les appels sortants vers les opérateurs.
 *
 * Circuit breakers configurés dans application.properties :
 *   resilience4j.circuitbreaker.instances.operator-payment.*
 *   resilience4j.circuitbreaker.instances.operator-disbursement.*
 *
 * Fallback : retourne FAILED immédiatement — la transaction est marquée FAILED
 * et le marchand peut réessayer quand l'opérateur est rétabli.
 *
 * À utiliser à la place d'appeler OperatorRegistry.get().method() directement
 * dans PaymentService et PayoutService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OperatorGateway {

    private final OperatorRegistry operatorRegistry;

    // ── Paiements (collection) ────────────────────────────────────────────────

    @CircuitBreaker(name = "operator-payment", fallbackMethod = "initiatePaymentFallback")
    public MobileMoneyOperator.OperatorInitResponse initiatePayment(
            OperatorType type, OperatorPaymentRequest request) {
        return operatorRegistry.get(type).initiatePayment(request);
    }

    @CircuitBreaker(name = "operator-payment", fallbackMethod = "checkPaymentStatusFallback")
    public TransactionStatus checkPaymentStatus(OperatorType type, String operatorReference) {
        return operatorRegistry.get(type).checkStatus(operatorReference);
    }

    // ── Décaissements (disbursement) ──────────────────────────────────────────

    @CircuitBreaker(name = "operator-disbursement", fallbackMethod = "initiateDisbursementFallback")
    public MobileMoneyOperator.OperatorInitResponse initiateDisbursement(
            OperatorType type, OperatorDisbursementRequest request) {
        return operatorRegistry.get(type).initiateDisbursement(request);
    }

    @CircuitBreaker(name = "operator-disbursement", fallbackMethod = "checkDisbursementStatusFallback")
    public TransactionStatus checkDisbursementStatus(OperatorType type, String operatorReference) {
        return operatorRegistry.get(type).checkDisbursementStatus(operatorReference);
    }

    // ── Remboursement (reversal) ──────────────────────────────────────────────

    /**
     * Demande à l'opérateur de créditer à nouveau le portefeuille mobile du client.
     * Protégé par le circuit breaker operator-payment.
     * Si l'opérateur ne supporte pas le reversal, renvoie null — l'appelant doit gérer le best-effort.
     */
    @CircuitBreaker(name = "operator-payment", fallbackMethod = "reversePaymentFallback")
    public OperatorRefundResult reversePayment(OperatorType type, String operatorReference,
                                               BigDecimal amount, String currency) {
        return operatorRegistry.get(type).reversePayment(operatorReference, amount, currency);
    }

    // ── Vérification de solde ─────────────────────────────────────────────────

    /**
     * Vérifie le solde float Ebithex chez l'opérateur.
     * Circuit breaker operator-balance (séparé pour ne pas polluer les métriques payment).
     */
    @CircuitBreaker(name = "operator-balance", fallbackMethod = "checkBalanceFallback")
    public MobileMoneyOperator.BalanceResult checkBalance(OperatorType type) {
        return operatorRegistry.get(type).checkBalance();
    }

    // ── Fallbacks ─────────────────────────────────────────────────────────────

    private MobileMoneyOperator.OperatorInitResponse initiatePaymentFallback(
            OperatorType type, OperatorPaymentRequest req, Throwable t) {
        log.error("Circuit breaker ouvert — opérateur {} (payment): {}", type, t.getMessage());
        return MobileMoneyOperator.OperatorInitResponse.failed(
            "Service " + type + " temporairement indisponible — réessayez dans quelques minutes");
    }

    private TransactionStatus checkPaymentStatusFallback(
            OperatorType type, String ref, Throwable t) {
        log.warn("Circuit breaker ouvert — opérateur {} (checkStatus): {}", type, t.getMessage());
        return TransactionStatus.PROCESSING; // Conserver le statut en cours
    }

    private OperatorRefundResult reversePaymentFallback(
            OperatorType type, String ref, BigDecimal amount, String currency, Throwable t) {
        log.error("Circuit breaker ouvert — opérateur {} (reversal): {}", type, t.getMessage());
        return OperatorRefundResult.failure("Service " + type + " indisponible — reversal non effectué");
    }

    private MobileMoneyOperator.BalanceResult checkBalanceFallback(OperatorType type, Throwable t) {
        log.warn("Circuit breaker ouvert — opérateur {} (checkBalance): {}", type, t.getMessage());
        return MobileMoneyOperator.BalanceResult.unavailable(
            "Service " + type + " temporairement indisponible — solde non disponible");
    }

    private MobileMoneyOperator.OperatorInitResponse initiateDisbursementFallback(
            OperatorType type, OperatorDisbursementRequest req, Throwable t) {
        log.error("Circuit breaker ouvert — opérateur {} (disbursement): {}", type, t.getMessage());
        return MobileMoneyOperator.OperatorInitResponse.failed(
            "Service " + type + " temporairement indisponible — réessayez dans quelques minutes");
    }

    private TransactionStatus checkDisbursementStatusFallback(
            OperatorType type, String ref, Throwable t) {
        log.warn("Circuit breaker ouvert — opérateur {} (checkDisbursementStatus): {}", type, t.getMessage());
        return TransactionStatus.PROCESSING;
    }
}