package com.ebithex.payment.application;

import com.ebithex.merchant.domain.Merchant;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Vérifie les plafonds journaliers et mensuels avant d'accepter un paiement.
 *
 * Les plafonds sont configurables par marchand via les champs
 * {@code dailyPaymentLimit} et {@code monthlyPaymentLimit} de l'entité Merchant.
 * NULL = pas de limite.
 *
 * Seules les transactions du schéma prod (public) sont comptabilisées — les données sandbox
 * sont invisibles car le pool sandbox utilise un search_path séparé.
 * Statuts pris en compte : PENDING, PROCESSING, SUCCESS — pour éviter que
 * des transactions en cours ne soient pas comptées.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionLimitService {

    private final TransactionRepository transactionRepository;

    /**
     * Vérifie que l'ajout de {@code amount} ne dépasse pas les plafonds du marchand.
     *
     * @throws EbithexException DAILY_LIMIT_EXCEEDED ou MONTHLY_LIMIT_EXCEEDED
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void checkLimits(Merchant merchant, BigDecimal amount) {
        if (SandboxContextHolder.isSandbox()) return;  // aucune limite en mode sandbox

        UUID merchantId = merchant.getId();

        if (merchant.getDailyPaymentLimit() != null) {
            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
            BigDecimal todaySum = transactionRepository.sumActiveAmountSince(merchantId, startOfDay);
            if (todaySum.add(amount).compareTo(merchant.getDailyPaymentLimit()) > 0) {
                log.warn("Plafond journalier dépassé: merchant={} déjà={} demandé={} limite={}",
                    merchantId, todaySum, amount, merchant.getDailyPaymentLimit());
                throw new EbithexException(ErrorCode.DAILY_LIMIT_EXCEEDED,
                    "Plafond journalier atteint: " + merchant.getDailyPaymentLimit() + " XOF/jour");
            }
        }

        if (merchant.getMonthlyPaymentLimit() != null) {
            LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
            BigDecimal monthSum = transactionRepository.sumActiveAmountSince(merchantId, startOfMonth);
            if (monthSum.add(amount).compareTo(merchant.getMonthlyPaymentLimit()) > 0) {
                log.warn("Plafond mensuel dépassé: merchant={} déjà={} demandé={} limite={}",
                    merchantId, monthSum, amount, merchant.getMonthlyPaymentLimit());
                throw new EbithexException(ErrorCode.MONTHLY_LIMIT_EXCEEDED,
                    "Plafond mensuel atteint: " + merchant.getMonthlyPaymentLimit() + " XOF/mois");
            }
        }
    }
}