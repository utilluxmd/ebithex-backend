package com.ebithex.settlement.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Job quotidien de règlement — s'exécute chaque jour à 01:00 UTC.
 *
 * Il génère les batches de règlement pour la journée J-1 (00:00 → 23:59).
 * Les batches sont créés en statut PENDING et confirmés manuellement
 * (ou via webhook bancaire) via POST /internal/settlement/{id}/settle.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DailySettlementJob {

    private final SettlementService settlementService;

    @Scheduled(cron = "${ebithex.settlement.cron:0 0 1 * * *}")
    public void runDailySettlement() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime from  = yesterday.atStartOfDay();
        LocalDateTime to    = yesterday.atTime(LocalTime.MAX);

        log.info("Démarrage cycle de règlement quotidien pour J-1 : {} → {}", from, to);
        try {
            int count = settlementService.runSettlementCycle(from, to);
            log.info("Cycle de règlement quotidien terminé: {} batch(es) créé(s)", count);
        } catch (Exception e) {
            log.error("Erreur lors du cycle de règlement quotidien", e);
        }
    }
}
