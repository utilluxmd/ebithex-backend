package com.ebithex.operatorfloat.application;

import com.ebithex.operatorfloat.domain.OperatorFloat;
import com.ebithex.operatorfloat.infrastructure.OperatorFloatRepository;
import com.ebithex.shared.audit.AuditLogService;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.event.FloatLowBalanceEvent;
import com.ebithex.shared.event.PaymentStatusChangedEvent;
import com.ebithex.shared.event.PayoutStatusChangedEvent;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;

/**
 * Gestion du float opérateur Ebithex.
 *
 * Flux :
 *  - Payment SUCCESS  → crédit float (Ebithex encaisse depuis l'opérateur)
 *  - Payout initié    → débit float  (Ebithex décaisse vers bénéficiaire)
 *  - Payout FAILED    → remboursement float
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorFloatService {

    private final OperatorFloatRepository floatRepository;
    private final AuditLogService         auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    // ── Listeners d'événements ───────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        if (event.newStatus() == TransactionStatus.SUCCESS
                && event.previousStatus() != TransactionStatus.SUCCESS) {
            // Crédit du float avec le montant net (frais déduits)
            BigDecimal amount = event.netAmount() != null ? event.netAmount() : event.amount();
            creditFloat(event.operator(), amount, event.ebithexReference());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPayoutStatusChanged(PayoutStatusChangedEvent event) {
        if (event.newStatus() == TransactionStatus.FAILED
                && event.previousStatus() != TransactionStatus.FAILED) {
            // Payout échoué → rembourser le float
            BigDecimal amount = event.netAmount() != null ? event.netAmount() : event.amount();
            creditFloat(event.operator(), amount, event.ebithexReference() + "-REFUND");
        }
    }

    // ── API publique ─────────────────────────────────────────────────────────

    /**
     * Débiter le float avant de soumettre un décaissement.
     * Appelé par PayoutService avant l'appel opérateur.
     */
    @Transactional
    public void debitFloat(OperatorType operator, BigDecimal amount, String reference) {
        OperatorFloat operatorFloat = getOrCreate(operator);
        if (operatorFloat.getBalance().compareTo(amount) < 0) {
            throw new EbithexException(ErrorCode.INSUFFICIENT_OPERATOR_FLOAT,
                "Float insuffisant pour l'opérateur " + operator
                    + " — solde=" + operatorFloat.getBalance()
                    + ", demandé=" + amount);
        }
        operatorFloat.setBalance(operatorFloat.getBalance().subtract(amount));
        floatRepository.save(operatorFloat);
        log.info("Float débité: opérateur={} montant={} ref={} solde_après={}",
            operator, amount, reference, operatorFloat.getBalance());

        checkLowBalance(operatorFloat);
    }

    @Transactional(readOnly = true)
    public List<OperatorFloat> getAllFloats() {
        return floatRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<OperatorFloat> getFloatsBelowThreshold() {
        return floatRepository.findBelowThreshold();
    }

    @Transactional
    public OperatorFloat adjustFloat(OperatorType operator, BigDecimal newBalance,
                                     BigDecimal newThreshold) {
        OperatorFloat operatorFloat = getOrCreate(operator);
        BigDecimal previousBalance = operatorFloat.getBalance();
        operatorFloat.setBalance(newBalance);
        if (newThreshold != null) operatorFloat.setLowBalanceThreshold(newThreshold);
        OperatorFloat saved = floatRepository.save(operatorFloat);
        auditLogService.record("FLOAT_ADJUSTED", "OperatorFloat", operator.name(),
            "{\"operator\":\"" + operator.name()
                + "\",\"previousBalance\":" + previousBalance
                + ",\"newBalance\":" + newBalance + "}");
        return saved;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Transactional
    public void creditFloat(OperatorType operator, BigDecimal amount, String reference) {
        OperatorFloat operatorFloat = getOrCreate(operator);
        operatorFloat.setBalance(operatorFloat.getBalance().add(amount));
        floatRepository.save(operatorFloat);
        log.info("Float crédité: opérateur={} montant={} ref={} solde_après={}",
            operator, amount, reference, operatorFloat.getBalance());
    }

    private OperatorFloat getOrCreate(OperatorType operator) {
        return floatRepository.findById(operator).orElseGet(() -> {
            OperatorFloat newFloat = OperatorFloat.builder()
                .operatorType(operator)
                .balance(BigDecimal.ZERO)
                .lowBalanceThreshold(new BigDecimal("100000"))  // 100 000 XOF par défaut
                .build();
            return floatRepository.save(newFloat);
        });
    }

    private void checkLowBalance(OperatorFloat operatorFloat) {
        if (operatorFloat.getBalance().compareTo(operatorFloat.getLowBalanceThreshold()) < 0) {
            log.warn("FLOAT BAS — opérateur={} solde={} seuil={}",
                operatorFloat.getOperatorType(),
                operatorFloat.getBalance(),
                operatorFloat.getLowBalanceThreshold());
            eventPublisher.publishEvent(new FloatLowBalanceEvent(
                operatorFloat.getOperatorType(),
                operatorFloat.getBalance(),
                operatorFloat.getLowBalanceThreshold()
            ));
        }
    }
}