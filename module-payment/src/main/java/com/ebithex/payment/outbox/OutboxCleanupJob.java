package com.ebithex.payment.outbox;

import com.ebithex.shared.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Job de purge de la table outbox_events.
 *
 * Supprime les événements dans les états terminaux (DISPATCHED, FAILED) dont
 * l'âge dépasse la rétention configurée. Évite la croissance infinie de la table.
 *
 * Exécution : 3h du matin chaque nuit (hors des heures de pointe).
 * Rétention  : configurable via {@code ebithex.outbox.retention-days} (défaut : 30 jours).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxCleanupJob {

    private final OutboxEventRepository outboxEventRepository;

    @Value("${ebithex.outbox.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "0 0 3 * * *")   // 03:00 chaque nuit
    @Transactional
    public void purgeOldEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deleteOldProcessedEvents(cutoff);
        if (deleted > 0) {
            log.info("Outbox cleanup: {} événement(s) purgé(s) (antérieurs à {})", deleted, cutoff);
        }
    }
}