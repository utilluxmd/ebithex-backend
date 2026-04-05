package com.ebithex.wallet.application;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.event.PaymentStatusChangedEvent;
import com.ebithex.shared.event.PayoutStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Écoute les événements domaine pour mettre à jour le wallet marchand.
 *
 * Phase AFTER_COMMIT : on est certain que la transaction source a été
 * committée avant de modifier le wallet (cohérence garantie).
 *
 * Chaque handler s'exécute dans sa propre transaction (REQUIRES_NEW
 * déclaré dans WalletService) pour isoler les échecs.
 *
 * Idempotence : WalletService vérifie la contrainte unique
 * (ebithex_reference, type) avant chaque mouvement.
 *
 * Multi-devise : la devise est extraite directement de l'événement pour
 * créditer/débiter le bon wallet (merchant, currency).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WalletEventListener {

    private final WalletService walletService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        if (event.newStatus() != TransactionStatus.SUCCESS) return;
        Currency currency = event.currency() != null ? event.currency() : Currency.XOF;
        try {
            walletService.creditPayment(
                event.merchantId(),
                event.netAmount(),
                event.ebithexReference(),
                currency
            );
        } catch (Exception e) {
            log.error("Wallet credit failed for payment {} — manual reconciliation needed: {}",
                event.ebithexReference(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPayoutStatusChanged(PayoutStatusChangedEvent event) {
        Currency currency = event.currency() != null ? event.currency() : Currency.XOF;
        try {
            switch (event.newStatus()) {
                case PROCESSING -> {
                    if (event.previousStatus() == TransactionStatus.PENDING) {
                        walletService.debitPayout(
                            event.merchantId(),
                            event.amount(),
                            event.ebithexReference(),
                            currency
                        );
                    }
                }
                case SUCCESS -> walletService.confirmPayout(
                    event.merchantId(),
                    event.amount(),
                    event.ebithexReference(),
                    currency
                );
                case FAILED, EXPIRED -> walletService.refundPayout(
                    event.merchantId(),
                    event.amount(),
                    event.ebithexReference(),
                    currency
                );
                default -> { /* PENDING initial — aucune action */ }
            }
        } catch (Exception e) {
            log.error("Wallet update failed for payout {} — manual reconciliation needed: {}",
                event.ebithexReference(), e.getMessage());
        }
    }
}