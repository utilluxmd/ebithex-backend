package com.ebithex.payment.api;

import com.ebithex.payment.application.RegulatoryReportingService;
import com.ebithex.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Endpoints de reporting réglementaire BCEAO / UEMOA.
 *
 * <p>Accès restreint aux rôles FINANCE, ADMIN et SUPER_ADMIN.
 * Tous les endpoints sont préfixés {@code /internal/regulatory}.
 */
@RestController
@RequestMapping("/internal/regulatory")
@RequiredArgsConstructor
@Tag(name = "Réglementaire — BCEAO / UEMOA", description = "Rapports réglementaires (CTR, SAR, volumes mensuels)")
@SecurityRequirement(name = "bearerAuth")
public class RegulatoryReportingController {

    private final RegulatoryReportingService reportingService;

    // ── Rapport mensuel transactions ──────────────────────────────────────────

    /**
     * Rapport mensuel des transactions par opérateur / devise / statut.
     *
     * @param month Mois au format YYYY-MM (ex: 2026-03)
     */
    @GetMapping("/reports/transactions")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(
        summary = "Rapport mensuel des transactions",
        description = "Volumes et montants agrégés par opérateur, devise et statut. "
            + "Conforme à l'instruction 008-05-2015 de la BCEAO sur la monnaie électronique."
    )
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> monthlyTransactions(
            @Parameter(description = "Mois au format YYYY-MM", example = "2026-03")
            @RequestParam String month) {
        YearMonth ym = YearMonth.parse(month);
        return ResponseEntity.ok(ApiResponse.ok(reportingService.monthlyTransactionReport(ym)));
    }

    /**
     * Rapport mensuel des décaissements (payouts) par opérateur / devise / statut.
     *
     * @param month Mois au format YYYY-MM
     */
    @GetMapping("/reports/payouts")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(
        summary = "Rapport mensuel des décaissements",
        description = "Volumes et montants de décaissements agrégés par opérateur et statut."
    )
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> monthlyPayouts(
            @Parameter(description = "Mois au format YYYY-MM", example = "2026-03")
            @RequestParam String month) {
        YearMonth ym = YearMonth.parse(month);
        return ResponseEntity.ok(ApiResponse.ok(reportingService.monthlyPayoutReport(ym)));
    }

    // ── CTR ───────────────────────────────────────────────────────────────────

    /**
     * Currency Transaction Reports (CTR) — transactions dépassant le seuil déclaratoire BCEAO.
     *
     * <p>Seuil par défaut : 5 000 000 XOF (environ 7 500 EUR).
     * Tout établissement de paiement est tenu de déclarer ces transactions
     * à la Cellule Nationale de Traitement des Informations Financières (CENTIF).
     */
    @GetMapping("/reports/ctr")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(
        summary = "Currency Transaction Reports (CTR)",
        description = "Transactions dépassant le seuil déclaratoire (défaut 5 000 000 XOF). "
            + "À transmettre à la CENTIF conformément à la loi uniforme UEMOA sur le blanchiment."
    )
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> ctr(
            @Parameter(description = "Date de début (YYYY-MM-DD)", example = "2026-03-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Date de fin (YYYY-MM-DD, exclusive)", example = "2026-04-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Seuil en devise locale (null = 5 000 000 XOF)")
            @RequestParam(required = false) BigDecimal threshold) {
        return ResponseEntity.ok(ApiResponse.ok(
            reportingService.currencyTransactionReport(from, to, threshold)));
    }

    // ── SAR ───────────────────────────────────────────────────────────────────

    /**
     * Suspicious Activity Reports (SAR) — alertes AML générées sur la période.
     *
     * <p>Agrège les alertes du moteur AML (velocity, structuring, montants élevés,
     * sanctions) par marchand et type d'anomalie.
     * À transmettre à la CENTIF en cas d'activité suspecte caractérisée.
     */
    @GetMapping("/reports/aml-sar")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(
        summary = "Suspicious Activity Reports (SAR)",
        description = "Alertes AML consolidées par marchand et type d'anomalie. "
            + "Inclut les alertes de velocity, structuring, montants élevés et sanctions."
    )
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sar(
            @Parameter(description = "Date de début (YYYY-MM-DD)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Date de fin (YYYY-MM-DD, exclusive)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(
            reportingService.suspiciousActivityReport(from, to)));
    }

    // ── Résumé mensuel consolidé ──────────────────────────────────────────────

    /**
     * Résumé réglementaire mensuel consolidé.
     *
     * <p>Vue synthétique pour les rapports trimestriels BCEAO : total transactions,
     * volumes SUCCESS, frais collectés, alertes AML.
     */
    @GetMapping("/reports/summary")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(
        summary = "Résumé réglementaire mensuel",
        description = "Vue consolidée des transactions, payouts et alertes AML pour un mois donné. "
            + "Adapté aux rapports trimestriels soumis à la BCEAO."
    )
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary(
            @Parameter(description = "Mois au format YYYY-MM", example = "2026-03")
            @RequestParam String month) {
        YearMonth ym = YearMonth.parse(month);
        return ResponseEntity.ok(ApiResponse.ok(reportingService.regulatorySummary(ym)));
    }
}