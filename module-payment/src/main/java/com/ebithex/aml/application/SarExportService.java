package com.ebithex.aml.application;

import com.ebithex.aml.domain.AmlSeverity;
import com.ebithex.aml.domain.AmlStatus;
import com.ebithex.aml.dto.SarExportRecord;
import com.ebithex.aml.infrastructure.AmlAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service d'export SAR/CCF (Suspicious Activity Report / Currency Transaction Form).
 *
 * <p>Génère les données nécessaires à la déclaration aux autorités financières
 * (CENTIF, BCEAO/UEMOA) pour les alertes AML de haute sévérité non encore déclarées.
 *
 * <p>Seules les alertes {@code HIGH} et {@code CRITICAL} avec statut {@code OPEN}
 * ou {@code UNDER_REVIEW} sont incluses dans l'export, afin d'éviter les doubles
 * déclarations d'alertes déjà {@code REPORTED} ou {@code CLEARED}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SarExportService {

    private final AmlAlertRepository alertRepository;
    private final AmlScreeningService screeningService;

    /**
     * Sévérités incluses dans un export SAR : alertes significatives uniquement.
     */
    private static final List<AmlSeverity> SAR_SEVERITIES =
        List.of(AmlSeverity.HIGH, AmlSeverity.CRITICAL);

    /**
     * Statuts inclus : alertes en attente de déclaration.
     * CLEARED et REPORTED sont exclus pour éviter les doublons.
     */
    private static final List<AmlStatus> SAR_STATUSES =
        List.of(AmlStatus.OPEN, AmlStatus.UNDER_REVIEW);

    /**
     * Retourne les enregistrements SAR pour la période demandée.
     *
     * @param from début de période (inclus)
     * @param to   fin de période (inclus)
     * @return liste triée chronologiquement pour la déclaration
     */
    @Transactional(readOnly = true)
    public List<SarExportRecord> getExportRecords(LocalDateTime from, LocalDateTime to) {
        List<SarExportRecord> records = alertRepository
            .findForSarExport(SAR_SEVERITIES, SAR_STATUSES, from, to)
            .stream()
            .map(SarExportRecord::from)
            .toList();

        log.info("Export SAR : {} enregistrement(s) HIGH/CRITICAL entre {} et {}",
            records.size(), from, to);
        return records;
    }

    /**
     * Génère le contenu CSV complet (en-tête + lignes) pour l'export SAR.
     *
     * @param from début de période (inclus)
     * @param to   fin de période (inclus)
     * @return chaîne CSV prête à être téléchargée
     */
    @Transactional(readOnly = true)
    public String generateCsv(LocalDateTime from, LocalDateTime to) {
        List<SarExportRecord> records = getExportRecords(from, to);

        StringBuilder sb = new StringBuilder();
        sb.append(SarExportRecord.csvHeader()).append("\r\n");
        for (SarExportRecord rec : records) {
            sb.append(rec.toCsvRow()).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * Marque les alertes exportées comme {@code REPORTED} pour traçabilité.
     * À appeler après confirmation de l'envoi aux autorités.
     *
     * @param from      début de période
     * @param to        fin de période
     * @param reportedBy email de l'opérateur qui déclenche la déclaration
     * @return nombre d'alertes marquées REPORTED
     */
    @Transactional
    public int markAsReported(LocalDateTime from, LocalDateTime to, String reportedBy) {
        List<SarExportRecord> records = getExportRecords(from, to);
        int count = 0;
        for (SarExportRecord rec : records) {
            try {
                screeningService.review(rec.alertId(), AmlStatus.REPORTED,
                    "Déclaré aux autorités via export SAR", reportedBy);
                count++;
            } catch (Exception e) {
                // L'alerte a peut-être déjà été résolue entre temps — on continue
                log.warn("Impossible de marquer l'alerte {} comme REPORTED : {}", rec.alertId(), e.getMessage());
            }
        }
        log.info("Export SAR : {} alerte(s) marquée(s) REPORTED par {}", count, reportedBy);
        return count;
    }
}
