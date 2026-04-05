package com.ebithex.payment.application;

import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.event.PaymentStatusChangedEvent;
import com.ebithex.shared.event.PayoutStatusChangedEvent;
import com.ebithex.shared.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Job planifié qui expire les transactions et les payouts dont {@code expiresAt} est dépassé.
 *
 * <p>Seuls les statuts non-terminaux sont traités : PENDING et PROCESSING.
 * Les statuts SUCCESS et FAILED sont définitifs et ne peuvent pas expirer.
 *
 * <p>Chaque expiration publie un événement dans l'outbox pour déclencher
 * la notification webhook marchand (et le remboursement wallet pour les payouts).
 *
 * <p>Exécution : toutes les {@code ebithex.expiration.check-interval-ms} ms (défaut : 60 s).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionExpirationJob {

    private static final String EXPIRY_REASON_TX = "Transaction expirée — aucune confirmation reçue de l'opérateur dans le délai imparti";
    private static final String EXPIRY_REASON_PO = "Payout expiré — aucune confirmation reçue de l'opérateur";
    private static final List<TransactionStatus> EXPIRABLE = List.of(
        TransactionStatus.PENDING,
        TransactionStatus.PROCESSING
    );

    private final TransactionRepository transactionRepository;
    private final PayoutRepository      payoutRepository;
    private final OutboxWriter          outboxWriter;

    // ── Transactions (paiements entrants) ────────────────────────────────────

    @Scheduled(fixedDelayString = "${ebithex.expiration.check-interval-ms:60000}")
    @Transactional
    public void expireTransactions() {
        LocalDateTime now = LocalDateTime.now();
        List<Transaction> expired = transactionRepository.findExpiredPending(EXPIRABLE, now);

        if (expired.isEmpty()) {
            return;
        }
        log.info("Expiration job — {} transaction(s) à expirer", expired.size());

        for (Transaction tx : expired) {
            TransactionStatus previous = tx.getStatus();
            tx.setStatus(TransactionStatus.EXPIRED);
            tx.setFailureReason(EXPIRY_REASON_TX);
            transactionRepository.save(tx);

            outboxWriter.write(
                "Transaction",
                tx.getId(),
                "PaymentStatusChangedEvent",
                new PaymentStatusChangedEvent(
                    tx.getId(),
                    tx.getEbithexReference(),
                    tx.getMerchantReference(),
                    tx.getMerchantId(),
                    TransactionStatus.EXPIRED,
                    previous,
                    tx.getAmount(),
                    tx.getFeeAmount(),
                    tx.getNetAmount(),
                    tx.getCurrency(),
                    tx.getOperator()
                )
            );
            log.debug("Transaction expirée: {} (était {})", tx.getEbithexReference(), previous);
        }
        log.info("Expiration job — {} transaction(s) expirée(s)", expired.size());
    }

    // ── Payouts (décaissements) ───────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${ebithex.expiration.check-interval-ms:60000}")
    @Transactional
    public void expirePayouts() {
        LocalDateTime now = LocalDateTime.now();
        List<Payout> expired = payoutRepository.findExpiredPending(EXPIRABLE, now);

        if (expired.isEmpty()) {
            return;
        }
        log.info("Expiration job — {} payout(s) à expirer", expired.size());

        for (Payout payout : expired) {
            TransactionStatus previous = payout.getStatus();
            payout.setStatus(TransactionStatus.EXPIRED);
            payout.setFailureReason(EXPIRY_REASON_PO);
            payoutRepository.save(payout);

            outboxWriter.write(
                "Payout",
                payout.getId(),
                "PayoutStatusChangedEvent",
                new PayoutStatusChangedEvent(
                    payout.getId(),
                    payout.getEbithexReference(),
                    payout.getMerchantReference(),
                    payout.getMerchantId(),
                    TransactionStatus.EXPIRED,
                    previous,
                    payout.getAmount(),
                    payout.getFeeAmount(),
                    payout.getNetAmount(),
                    payout.getCurrency(),
                    payout.getOperator()
                )
            );
            log.debug("Payout expiré: {} (était {})", payout.getEbithexReference(), previous);
        }
        log.info("Expiration job — {} payout(s) expiré(s)", expired.size());
    }
}
