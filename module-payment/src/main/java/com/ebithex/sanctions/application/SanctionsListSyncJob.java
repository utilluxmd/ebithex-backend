package com.ebithex.sanctions.application;

import com.ebithex.sanctions.domain.SanctionsSyncLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Job hebdomadaire de synchronisation des listes de sanctions réglementaires.
 *
 * <p>Déclenché chaque dimanche à 05:00 UTC par défaut (hors pic de trafic).
 * Synchronise les trois listes automatiques :
 * <ul>
 *   <li>OFAC SDN (U.S. Treasury)</li>
 *   <li>Nations Unies — liste consolidée</li>
 *   <li>Union Européenne — liste consolidée</li>
 * </ul>
 *
 * <p>Peut être déclenché manuellement depuis l'API back-office via
 * {@code POST /internal/sanctions/sync} ou {@code POST /internal/sanctions/sync/{listName}}.
 *
 * <p>Chaque synchronisation est journalisée dans {@code sanctions_sync_log}.
 *
 * <p>Configuration :
 * <ul>
 *   <li>{@code ebithex.sanctions.sync.enabled} — active/désactive le job (défaut : {@code true})</li>
 *   <li>{@code ebithex.sanctions.sync.cron}    — expression cron (défaut : dimanche 05:00 UTC)</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SanctionsListSyncJob {

    private final SanctionsListSyncService syncService;

    @Value("${ebithex.sanctions.sync.enabled:true}")
    private boolean syncEnabled;

    /**
     * Synchronisation hebdomadaire de toutes les listes automatiques.
     * Cron configurable via {@code ebithex.sanctions.sync.cron}.
     */
    @Scheduled(cron = "${ebithex.sanctions.sync.cron:0 0 5 * * SUN}")
    public void runWeeklySync() {
        if (!syncEnabled) {
            log.info("Synchronisation des sanctions désactivée (ebithex.sanctions.sync.enabled=false)");
            return;
        }

        log.info("=== Début de la synchronisation hebdomadaire des listes de sanctions ===");
        List<SanctionsSyncLog> results = syncService.syncAll();

        int success = (int) results.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count();
        int failed  = (int) results.stream().filter(r -> "FAILED".equals(r.getStatus())).count();
        int total   = (int) results.stream().mapToInt(SanctionsSyncLog::getEntriesImported).sum();

        log.info("=== Fin de la synchronisation : {}/{} listes OK, {} entrées importées au total ===",
            success, results.size(), total);

        if (failed > 0) {
            log.error("=== {} liste(s) en échec — vérifier /internal/sanctions/sync/status ===", failed);
        }
    }
}
