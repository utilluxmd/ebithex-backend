package com.ebithex.payment.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job planifié — réconciliation automatique des relevés opérateurs.
 *
 * S'exécute chaque nuit à 02:30 UTC pour traiter tous les relevés
 * importés dans la journée et encore en statut PENDING.
 *
 * Le cron est configurable via la propriété :
 *   ebithex.reconciliation.cron (défaut : 0 30 2 * * *)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OperatorReconciliationJob {

    private final OperatorReconciliationService reconciliationService;

    @Scheduled(cron = "${ebithex.reconciliation.cron:0 30 2 * * *}")
    public void run() {
        log.info("=== Début du job de réconciliation opérateur ===");
        try {
            reconciliationService.reconcileAllPending();
        } catch (Exception e) {
            log.error("Erreur critique dans le job de réconciliation: {}", e.getMessage(), e);
        }
        log.info("=== Fin du job de réconciliation opérateur ===");
    }
}