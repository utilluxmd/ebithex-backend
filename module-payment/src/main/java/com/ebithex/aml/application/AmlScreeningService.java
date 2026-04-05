package com.ebithex.aml.application;

import com.ebithex.aml.domain.AmlAlert;
import com.ebithex.aml.domain.AmlSeverity;
import com.ebithex.aml.domain.AmlStatus;
import com.ebithex.aml.infrastructure.AmlAlertRepository;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.sanctions.application.SanctionsScreeningService;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service de détection AML (Anti-Money Laundering).
 *
 * Règles implémentées :
 *  1. VELOCITY_HOURLY   — > {@code maxTxPerHour} transactions non-test sur 1 heure → MEDIUM
 *  2. VELOCITY_DAILY    — > {@code maxTxPerDay}  transactions non-test sur 24 heures → HIGH
 *  3. VELOCITY_WEEKLY   — > {@code maxTxPerWeek} transactions non-test sur 7 jours → HIGH
 *  4. HIGH_AMOUNT       — montant > {@code highAmountThreshold} → HIGH
 *  5. STRUCTURING       — ≥ 3 transactions entre 80% et 100% du seuil en 24h → HIGH
 *
 * Comportement :
 *  - CRITICAL → bloque la transaction (lève EbithexException)
 *  - HIGH/MEDIUM/LOW → alerte créée, transaction poursuit son cours
 *  - Les transactions en test mode sont ignorées
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AmlScreeningService {

    private final AmlAlertRepository      alertRepository;
    private final TransactionRepository  transactionRepository;
    private final SanctionsScreeningService sanctionsScreeningService;

    @Value("${ebithex.aml.velocity.max-tx-per-hour:20}")
    private int maxTxPerHour;

    @Value("${ebithex.aml.velocity.max-tx-per-day:100}")
    private int maxTxPerDay;

    @Value("${ebithex.aml.velocity.max-tx-per-week:500}")
    private int maxTxPerWeek;

    @Value("${ebithex.aml.high-amount-threshold:5000000}")
    private BigDecimal highAmountThreshold;

    /**
     * Analyse une transaction et crée les alertes AML pertinentes.
     * Appelé après création de la transaction (dans la même transaction Spring),
     * AVANT l'appel à l'opérateur.
     *
     * @throws EbithexException AML_BLOCKED si une règle CRITICAL est déclenchée
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void screen(Transaction tx) {
        if (SandboxContextHolder.isSandbox()) return;

        // ── Sanctions check (priorité absolue) ───────────────────────────────
        SanctionsScreeningService.SanctionsCheckResult sanctions = sanctionsScreeningService.checkTransaction(tx);
        if (sanctions.hit()) {
            if (sanctions.requiresBlock()) {
                // Score ≥ blockThreshold → CRITICAL → blocage immédiat
                AmlAlert sanctionsAlert = buildAlert(tx, "SANCTIONS_HIT", AmlSeverity.CRITICAL,
                    "Correspondance confirmée (score=" + String.format("%.3f", sanctions.score())
                    + ") — " + sanctions.reason());
                alertRepository.save(sanctionsAlert);
                log.warn("SANCTIONS HIT (BLOCAGE, score={}) — tx={} merchant={} raison={}",
                    String.format("%.3f", sanctions.score()),
                    tx.getEbithexReference(), tx.getMerchantId(), sanctions.reason());
                throw new EbithexException(ErrorCode.AML_BLOCKED,
                    "Transaction bloquée — correspondance liste de sanctions : " + sanctions.reason());
            } else {
                // Score ∈ [reviewThreshold, blockThreshold[ → HIGH → révision, transaction poursuivie
                AmlAlert nearMissAlert = buildAlert(tx, "SANCTIONS_NEAR_MISS", AmlSeverity.HIGH,
                    "Correspondance probable (score=" + String.format("%.3f", sanctions.score())
                    + ") — révision requise — " + sanctions.reason());
                alertRepository.save(nearMissAlert);
                log.warn("SANCTIONS NEAR-MISS (RÉVISION, score={}) — tx={} merchant={} raison={}",
                    String.format("%.3f", sanctions.score()),
                    tx.getEbithexReference(), tx.getMerchantId(), sanctions.reason());
                // La transaction n'est PAS bloquée : l'équipe Conformité doit réviser l'alerte
            }
        }

        List<AmlAlert> alerts = new ArrayList<>();
        UUID merchantId = tx.getMerchantId();

        // Rule 1 — Velocity horaire
        long txLastHour = transactionRepository.countByMerchantIdAndCreatedAtAfter(
            merchantId, LocalDateTime.now().minusHours(1));
        if (txLastHour > maxTxPerHour) {
            alerts.add(buildAlert(tx, "VELOCITY_HOURLY", AmlSeverity.MEDIUM,
                txLastHour + " transactions sur la dernière heure (seuil: " + maxTxPerHour + ")"));
        }

        // Rule 2 — Velocity journalière
        long txLast24h = transactionRepository.countByMerchantIdAndCreatedAtAfter(
            merchantId, LocalDateTime.now().minusHours(24));
        if (txLast24h > maxTxPerDay) {
            alerts.add(buildAlert(tx, "VELOCITY_DAILY", AmlSeverity.HIGH,
                txLast24h + " transactions sur les 24 dernières heures (seuil: " + maxTxPerDay + ")"));
        }

        // Rule 3 — Velocity hebdomadaire
        long txLastWeek = transactionRepository.countByMerchantIdAndCreatedAtAfter(
            merchantId, LocalDateTime.now().minusDays(7));
        if (txLastWeek > maxTxPerWeek) {
            alerts.add(buildAlert(tx, "VELOCITY_WEEKLY", AmlSeverity.HIGH,
                txLastWeek + " transactions sur les 7 derniers jours (seuil: " + maxTxPerWeek + ")"));
        }

        // Rule 4 — Montant élevé
        if (tx.getAmount() != null && tx.getAmount().compareTo(highAmountThreshold) > 0) {
            alerts.add(buildAlert(tx, "HIGH_AMOUNT", AmlSeverity.HIGH,
                "Montant " + tx.getAmount() + " " + tx.getCurrency() + " dépasse le seuil " + highAmountThreshold));
        }

        // Rule 5 — Structuring (fractionnement)
        BigDecimal lowerBound = highAmountThreshold.multiply(new BigDecimal("0.80"));
        long structuringCount = transactionRepository.countStructuringAttempts(
            merchantId, lowerBound, highAmountThreshold, LocalDateTime.now().minusHours(24));
        if (structuringCount >= 3) {
            alerts.add(buildAlert(tx, "STRUCTURING", AmlSeverity.HIGH,
                structuringCount + " transactions entre " + lowerBound + " et " + highAmountThreshold
                    + " en 24h — possible fractionnement"));
        }

        if (!alerts.isEmpty()) {
            alertRepository.saveAll(alerts);
            boolean hasCritical = alerts.stream()
                .anyMatch(a -> a.getSeverity() == AmlSeverity.CRITICAL);
            if (hasCritical) {
                log.warn("AML CRITICAL — transaction bloquée: {} merchant={}", tx.getEbithexReference(), merchantId);
                throw new EbithexException(ErrorCode.AML_BLOCKED,
                    "Transaction bloquée suite à une alerte AML critique. Contactez le support Ebithex.");
            }
            log.warn("AML — {} alerte(s) créée(s) pour transaction {} merchant={}",
                alerts.size(), tx.getEbithexReference(), merchantId);
        }
    }

    /**
     * Marque une alerte comme reviewée (CLEARED ou REPORTED).
     */
    @Transactional
    public AmlAlert review(UUID alertId, AmlStatus newStatus, String resolutionNote, String reviewedBy) {
        AmlAlert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new EbithexException(ErrorCode.AML_ALERT_NOT_FOUND, "Alerte AML introuvable: " + alertId));
        if (alert.getStatus() == AmlStatus.CLEARED || alert.getStatus() == AmlStatus.REPORTED) {
            throw new EbithexException(ErrorCode.AML_ALREADY_RESOLVED, "Alerte déjà résolue: " + alert.getStatus());
        }
        alert.setStatus(newStatus);
        alert.setResolutionNote(resolutionNote);
        alert.setReviewedBy(reviewedBy);
        alert.setReviewedAt(LocalDateTime.now());
        return alertRepository.save(alert);
    }

    private AmlAlert buildAlert(Transaction tx, String ruleCode, AmlSeverity severity, String details) {
        return AmlAlert.builder()
            .merchantId(tx.getMerchantId())
            .transactionId(tx.getId())
            .ruleCode(ruleCode)
            .severity(severity)
            .status(AmlStatus.OPEN)
            .details(details)
            .amount(tx.getAmount())
            .currency(tx.getCurrency() != null ? tx.getCurrency().name() : null)
            .build();
    }
}