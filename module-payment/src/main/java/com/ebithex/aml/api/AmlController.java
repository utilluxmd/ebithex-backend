package com.ebithex.aml.api;

import com.ebithex.aml.application.AmlScreeningService;
import com.ebithex.aml.application.SarExportService;
import com.ebithex.aml.domain.AmlAlert;
import com.ebithex.aml.domain.AmlStatus;
import com.ebithex.aml.dto.AmlAlertResponse;
import com.ebithex.aml.dto.AmlReviewRequest;
import com.ebithex.aml.dto.SarExportRecord;
import com.ebithex.aml.infrastructure.AmlAlertRepository;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Back-office AML API.
 *
 * <pre>
 * GET  /internal/aml/alerts                  — liste les alertes avec filtres
 * GET  /internal/aml/alerts/{id}             — détail d'une alerte
 * PUT  /internal/aml/alerts/{id}             — review (CLEARED / REPORTED)
 * GET  /internal/aml/alerts/export/sar       — export SAR/CCF (CSV)
 * POST /internal/aml/alerts/export/sar/mark  — marquer les alertes comme REPORTED
 * </pre>
 */
@RestController
@RequestMapping("/internal/aml")
@RequiredArgsConstructor
@Tag(name = "Back-office — AML")
public class AmlController {

    private final AmlAlertRepository  alertRepository;
    private final AmlScreeningService screeningService;
    private final SarExportService    sarExportService;

    // ── Liste et détail ───────────────────────────────────────────────────────

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Lister les alertes AML",
               description = "Retourne les alertes AML paginées avec filtres optionnels (statut, marchand, période). Défaut : 30 derniers jours.")
    public Page<AmlAlertResponse> listAlerts(
            @RequestParam(required = false) AmlStatus status,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now();

        return alertRepository
            .findForReview(status, merchantId, effectiveFrom, effectiveTo, pageable)
            .map(AmlAlertResponse::from);
    }

    @GetMapping("/alerts/{id}")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Détail d'une alerte AML", description = "Retourne le détail complet d'une alerte AML par son UUID.")
    public ResponseEntity<AmlAlertResponse> getAlert(@PathVariable UUID id) {
        return alertRepository.findById(id)
            .map(a -> ResponseEntity.ok(AmlAlertResponse.from(a)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/alerts/{id}")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Classer / Signaler une alerte AML",
               description = "Met à jour le statut d'une alerte à `CLEARED` (faux positif) ou `REPORTED` (signalé aux autorités). Une alerte déjà résolue ne peut pas être modifiée.")
    public AmlAlertResponse reviewAlert(
            @PathVariable UUID id,
            @Valid @RequestBody AmlReviewRequest req,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        AmlAlert updated = screeningService.review(id, req.status(), req.resolutionNote(),
            principal != null ? principal.email() : "system");
        return AmlAlertResponse.from(updated);
    }

    // ── Export SAR/CCF ────────────────────────────────────────────────────────

    /**
     * Génère un export CSV des alertes HIGH/CRITICAL pour déclaration aux autorités
     * financières (CENTIF/BCEAO). Inclut uniquement les alertes OPEN et UNDER_REVIEW
     * pour éviter les doublons avec les déclarations précédentes.
     *
     * <p>Accès réservé au rôle COMPLIANCE uniquement.
     */
    @GetMapping(value = "/alerts/export/sar", produces = "text/csv")
    @PreAuthorize("hasRole('COMPLIANCE')")
    @Operation(
        summary = "Export SAR/CCF (CSV)",
        description = """
            Génère un fichier CSV des alertes AML **HIGH** et **CRITICAL** au statut `OPEN` ou `UNDER_REVIEW`
            pour déclaration aux autorités financières (CENTIF/BCEAO/UEMOA).

            Le fichier est retourné avec `Content-Disposition: attachment` (téléchargement automatique).
            Encodage UTF-8. Format RFC 4180.

            **Workflow recommandé** :
            1. `GET /preview` → vérifier le nombre d'alertes
            2. `GET /export/sar` → télécharger le CSV
            3. Transmettre aux autorités compétentes
            4. `POST /mark-reported` → marquer comme déclarées (irréversible)

            ⚠️ Rôle `COMPLIANCE` uniquement."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "CSV généré avec succès (peut être vide si aucune alerte)"),
        @ApiResponse(responseCode = "403", description = "Accès refusé — rôle COMPLIANCE requis")
    })
    public ResponseEntity<byte[]> exportSar(
            @Parameter(description = "Début de la période (ISO 8601). Défaut : 30 jours avant aujourd'hui.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Fin de la période (ISO 8601). Défaut : maintenant.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now();

        String csvContent = sarExportService.generateCsv(effectiveFrom, effectiveTo);

        String filename = "sar_export_"
            + effectiveFrom.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            + "_"
            + effectiveTo.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            + ".csv";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Retourne le résumé JSON des alertes SAR pour la période (sans téléchargement).
     * Utile pour afficher le nombre d'alertes avant de déclencher l'export CSV.
     */
    @GetMapping("/alerts/export/sar/preview")
    @PreAuthorize("hasAnyRole('COMPLIANCE','SUPER_ADMIN')")
    @Operation(
        summary = "Aperçu SAR/CCF (JSON)",
        description = """
            Retourne un résumé JSON du nombre d'alertes HIGH/CRITICAL à déclarer sans générer le CSV.
            Utile pour afficher un bilan avant de déclencher l'export.

            **Exemple de réponse** :
            ```json
            {
              "totalAlerts": 12,
              "criticalAlerts": 3,
              "highAlerts": 9,
              "from": "2026-03-01T00:00:00",
              "to": "2026-03-31T23:59:59"
            }
            ```

            Rôles : `COMPLIANCE` · `SUPER_ADMIN`"""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Résumé retourné (totalAlerts peut être 0)"),
        @ApiResponse(responseCode = "403", description = "Accès refusé — rôle COMPLIANCE ou SUPER_ADMIN requis")
    })
    public ResponseEntity<Map<String, Object>> previewSar(
            @Parameter(description = "Début de la période (ISO 8601). Défaut : 30 jours avant aujourd'hui.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Fin de la période (ISO 8601). Défaut : maintenant.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now();

        List<SarExportRecord> records = sarExportService.getExportRecords(effectiveFrom, effectiveTo);
        long criticalCount = records.stream()
            .filter(r -> r.severity() == com.ebithex.aml.domain.AmlSeverity.CRITICAL)
            .count();

        return ResponseEntity.ok(Map.of(
            "totalAlerts",    records.size(),
            "criticalAlerts", criticalCount,
            "highAlerts",     records.size() - criticalCount,
            "from",           effectiveFrom.toString(),
            "to",             effectiveTo.toString()
        ));
    }

    /**
     * Marque les alertes de la période comme REPORTED après confirmation de l'envoi
     * aux autorités. Opération irréversible — à n'utiliser qu'après transmission officielle.
     */
    @PostMapping("/alerts/export/sar/mark-reported")
    @PreAuthorize("hasRole('COMPLIANCE')")
    @Operation(
        summary = "Marquer SAR/CCF comme déclaré",
        description = """
            Passe toutes les alertes HIGH/CRITICAL (`OPEN` + `UNDER_REVIEW`) de la période au statut `REPORTED`.

            ⚠️ **Opération irréversible.** À n'exécuter qu'après confirmation de la transmission officielle
            du CSV aux autorités compétentes (CENTIF, BCEAO).

            **Exemple de réponse** :
            ```json
            {
              "markedAsReported": 12,
              "reportedBy": "compliance@ebithex.io",
              "from": "2026-03-01T00:00:00",
              "to": "2026-03-31T23:59:59"
            }
            ```

            ⚠️ Rôle `COMPLIANCE` uniquement."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alertes marquées comme déclarées (markedAsReported peut être 0)"),
        @ApiResponse(responseCode = "403", description = "Accès refusé — rôle COMPLIANCE requis")
    })
    public ResponseEntity<Map<String, Object>> markSarAsReported(
            @Parameter(description = "Début de la période (ISO 8601). Défaut : 30 jours avant aujourd'hui.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Fin de la période (ISO 8601). Défaut : maintenant.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now();

        String reportedBy = principal != null ? principal.email() : "system";
        int marked = sarExportService.markAsReported(effectiveFrom, effectiveTo, reportedBy);

        return ResponseEntity.ok(Map.of(
            "markedAsReported", marked,
            "reportedBy",       reportedBy,
            "from",             effectiveFrom.toString(),
            "to",               effectiveTo.toString()
        ));
    }
}
