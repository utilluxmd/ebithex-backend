package com.ebithex.merchant.application;

import com.ebithex.merchant.domain.ApiKey;
import com.ebithex.merchant.infrastructure.ApiKeyRepository;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.shared.event.ApiKeyAgingReminderEvent;
import com.ebithex.shared.event.ApiKeyForcedRotationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Job quotidien de surveillance du vieillissement des clés API.
 *
 * <p>Deux cas traités :
 * <ol>
 *   <li><b>Rappel de rotation</b> : clés actives depuis plus de
 *       {@code ebithex.security.api-key.aging-alert-days} jours (défaut 60).
 *       Un email est envoyé au marchand, puis au plus une fois tous les 30 jours.</li>
 *   <li><b>Rotation forcée dépassée</b> : clés dont {@code rotationRequiredDays}
 *       est défini et dont la date de création + rotationRequiredDays est dépassée.
 *       La clé est désactivée automatiquement — le marchand doit en créer une nouvelle.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAgingJob {

    private final ApiKeyRepository       apiKeyRepository;
    private final MerchantRepository     merchantRepository;
    private final ApiKeyService          apiKeyService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${ebithex.security.api-key.aging-alert-days:60}")
    private int agingAlertDays;

    // ── Rappels de rotation ───────────────────────────────────────────────────

    /**
     * Envoie des rappels aux marchands dont les clés dépassent le seuil de vieillissement.
     * Exécution quotidienne à 08h00 UTC.
     */
    @Scheduled(cron = "${ebithex.security.api-key.aging-cron:0 0 8 * * *}")
    @Transactional
    public void sendAgingReminders() {
        LocalDateTime agingCutoff    = LocalDateTime.now().minusDays(agingAlertDays);
        LocalDateTime reminderCutoff = LocalDateTime.now().minusDays(30);

        List<ApiKey> aging = apiKeyRepository.findAging(agingCutoff, reminderCutoff);
        if (aging.isEmpty()) return;

        log.info("ApiKeyAgingJob: {} clé(s) à notifier", aging.size());

        for (ApiKey key : aging) {
            merchantRepository.findById(key.getMerchantId()).ifPresent(merchant -> {
                long keyAgeDays = ChronoUnit.DAYS.between(key.getCreatedAt(), LocalDateTime.now());
                String label = key.getLabel() != null ? key.getLabel() : key.getPrefix() + key.getKeyHint();

                eventPublisher.publishEvent(new ApiKeyAgingReminderEvent(
                    merchant.getEmail(),
                    merchant.getBusinessName(),
                    key.getId(),
                    label,
                    key.getKeyHint(),
                    keyAgeDays,
                    agingAlertDays
                ));
                apiKeyService.markAgingReminderSent(key.getId());
            });
        }
    }

    // ── Rotation forcée dépassée ──────────────────────────────────────────────

    /**
     * Désactive les clés ayant dépassé leur seuil de rotation obligatoire.
     * Exécution quotidienne à 08h05 UTC (après les rappels).
     */
    @Scheduled(cron = "${ebithex.security.api-key.forced-rotation-cron:0 5 8 * * *}")
    @Transactional
    public void enforceRotationPolicy() {
        List<ApiKey> overdue = apiKeyRepository.findOverdueForForcedRotation();
        if (overdue.isEmpty()) return;

        log.warn("ApiKeyAgingJob: {} clé(s) en dépassement de rotation forcée — désactivation",
            overdue.size());

        for (ApiKey key : overdue) {
            key.setActive(false);
            apiKeyRepository.save(key);

            merchantRepository.findById(key.getMerchantId()).ifPresent(merchant -> {
                String label = key.getLabel() != null ? key.getLabel() : key.getPrefix() + key.getKeyHint();

                eventPublisher.publishEvent(new ApiKeyForcedRotationEvent(
                    merchant.getEmail(),
                    merchant.getBusinessName(),
                    key.getId(),
                    label,
                    key.getKeyHint(),
                    key.getRotationRequiredDays()
                ));
                log.warn("Clé désactivée (rotation forcée dépassée): keyId={} merchantId={}",
                    key.getId(), key.getMerchantId());
            });
        }
    }
}
