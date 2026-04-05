package com.ebithex.sanctions.api;

import com.ebithex.sanctions.application.SanctionsListSyncService;
import com.ebithex.sanctions.application.SanctionsScreeningService;
import com.ebithex.sanctions.domain.SanctionsEntry;
import com.ebithex.sanctions.domain.SanctionsSyncLog;
import com.ebithex.sanctions.infrastructure.SanctionsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API back-office pour la gestion des listes de sanctions.
 *
 * <p>Rôles requis : {@code COMPLIANCE}, {@code ADMIN}, {@code SUPER_ADMIN}.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /internal/sanctions/entries}              — Lister les entrées actives</li>
 *   <li>{@code POST /internal/sanctions/check}                — Vérifier un nom / pays</li>
 *   <li>{@code DELETE /internal/sanctions/entries/{listName}} — Supprimer une liste</li>
 *   <li>{@code POST /internal/sanctions/sync}                 — Synchroniser toutes les listes</li>
 *   <li>{@code POST /internal/sanctions/sync/{listName}}      — Synchroniser une liste</li>
 *   <li>{@code GET  /internal/sanctions/sync/status}          — Statut des dernières synchronisations</li>
 *   <li>{@code POST /internal/sanctions/import/{listName}}    — Importer une liste depuis un CSV</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/sanctions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Sanctions — Back-office",
     description = "Gestion des listes de sanctions réglementaires (OFAC, ONU, UE) et synchronisation automatique.")
public class SanctionsAdminController {

    private static final Set<String> VALID_LIST_NAMES = Set.of(
        "OFAC_SDN", "UN_CONSOLIDATED", "EU_CONSOLIDATED", "ECOWAS_LOCAL", "CUSTOM"
    );
    private static final Set<String> AUTO_SYNC_LISTS = Set.of(
        "OFAC_SDN", "UN_CONSOLIDATED", "EU_CONSOLIDATED"
    );

    private final SanctionsRepository       sanctionsRepository;
    private final SanctionsScreeningService screeningService;
    private final SanctionsListSyncService  syncService;

    // ── Consultation des entrées ──────────────────────────────────────────────

    @GetMapping("/entries")
    @Operation(summary = "Lister les entrées de sanctions actives",
               description = "Retourne toutes les entrées actives de la base de sanctions (toutes listes confondues).")
    public ResponseEntity<Map<String, Object>> listEntries() {
        List<SanctionsEntry> entries = sanctionsRepository.findByIsActiveTrue();
        List<Map<String, Object>> data = entries.stream()
            .map(e -> Map.<String, Object>of(
                "id",          e.getId(),
                "listName",    e.getListName(),
                "entityName",  e.getEntityName(),
                "countryCode", e.getCountryCode()  != null ? e.getCountryCode()  : "",
                "entityType",  e.getEntityType()   != null ? e.getEntityType()   : ""
            ))
            .toList();
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    // ── Vérification ─────────────────────────────────────────────────────────

    @PostMapping("/check")
    @Operation(summary = "Vérifier un nom ou un pays contre les listes de sanctions",
               description = """
               Retourne le résultat du screening avec le score de similarité Jaro-Winkler.

               - `hit=true` + `requiresBlock=true`  → score ≥ seuil de blocage (0.95 par défaut)
               - `hit=true` + `requiresBlock=false` → score ∈ [seuil révision, seuil blocage[ (0.80–0.95) — near-miss, révision recommandée
               - `hit=false` → aucune correspondance significative
               """)
    public ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, String> body) {
        String name        = body.get("name");
        String countryCode = body.get("countryCode");

        boolean countryHit = countryCode != null && screeningService.isHighRiskCountry(countryCode);
        SanctionsScreeningService.SanctionsCheckResult nameResult =
            name != null ? screeningService.checkName(name) : SanctionsScreeningService.SanctionsCheckResult.noHit();

        boolean anyHit     = countryHit || nameResult.hit();
        boolean blockNeeded = countryHit || nameResult.requiresBlock();

        String reason = null;
        if (countryHit)       reason = "Pays à haut risque : " + countryCode;
        else if (nameResult.hit()) reason = nameResult.reason();

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("hit",           anyHit);
        data.put("requiresBlock", blockNeeded);
        data.put("score",         nameResult.score());
        data.put("matchedList",   nameResult.matchedList()   != null ? nameResult.matchedList()   : "");
        data.put("matchedEntity", nameResult.matchedEntity() != null ? nameResult.matchedEntity() : "");
        data.put("reason",        reason != null ? reason : "");

        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @DeleteMapping("/entries/{listName}")
    @Operation(summary = "Supprimer toutes les entrées d'une liste",
               description = "Purge toutes les entrées de la liste spécifiée. Utilisez ensuite `/sync/{listName}` ou `/import/{listName}` pour recharger.")
    public ResponseEntity<Map<String, Object>> deleteList(
            @PathVariable @Parameter(description = "OFAC_SDN | UN_CONSOLIDATED | EU_CONSOLIDATED | ECOWAS_LOCAL | CUSTOM") String listName) {
        validateListName(listName);
        long countBefore = sanctionsRepository.countByListNameAndIsActiveTrue(listName);
        sanctionsRepository.deleteByListName(listName);
        log.info("Liste de sanctions supprimée : {} ({} entrées)", listName, countBefore);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of("listName", listName, "deletedCount", countBefore)
        ));
    }

    // ── Synchronisation automatique ───────────────────────────────────────────

    @PostMapping("/sync")
    @Operation(summary = "Synchroniser toutes les listes automatiques",
               description = "Déclenche la synchronisation de OFAC_SDN, UN_CONSOLIDATED et EU_CONSOLIDATED depuis leurs sources officielles. Opération asynchrone de facto (peut durer plusieurs minutes).")
    public ResponseEntity<Map<String, Object>> syncAll() {
        log.info("Synchronisation manuelle de toutes les listes déclenchée");
        List<SanctionsSyncLog> results = syncService.syncAll();
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", results.stream().map(this::toSyncLogMap).toList()
        ));
    }

    @PostMapping("/sync/{listName}")
    @Operation(summary = "Synchroniser une liste spécifique",
               description = "Déclenche la synchronisation d'une des trois listes automatiques (OFAC_SDN, UN_CONSOLIDATED, EU_CONSOLIDATED) depuis sa source officielle.")
    public ResponseEntity<Map<String, Object>> syncList(
            @PathVariable @Parameter(description = "OFAC_SDN | UN_CONSOLIDATED | EU_CONSOLIDATED") String listName) {
        if (!AUTO_SYNC_LISTS.contains(listName)) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "Synchronisation automatique disponible uniquement pour : "
                    + String.join(", ", AUTO_SYNC_LISTS)
                    + " — utilisez POST /import/{listName} pour les listes manuelles."
            ));
        }
        log.info("Synchronisation manuelle déclenchée : {}", listName);
        SanctionsSyncLog result = syncService.syncList(listName);
        return ResponseEntity.ok(Map.of("success", true, "data", toSyncLogMap(result)));
    }

    // ── Statut des synchronisations ───────────────────────────────────────────

    @GetMapping("/sync/status")
    @Operation(summary = "Statut des dernières synchronisations",
               description = "Retourne le dernier log de synchronisation pour chaque liste connue.")
    public ResponseEntity<Map<String, Object>> syncStatus() {
        Map<String, SanctionsSyncLog> status = syncService.getSyncStatus();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        status.forEach((listName, logEntry) -> data.put(listName, toSyncLogMap(logEntry)));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @GetMapping("/sync/history")
    @Operation(summary = "Historique récent des synchronisations",
               description = "Retourne les N derniers logs toutes listes confondues (défaut : 20).")
    public ResponseEntity<Map<String, Object>> syncHistory(
            @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.min(limit, 100);
        List<SanctionsSyncLog> logs = syncService.getRecentLogs(safeLimit);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", logs.stream().map(this::toSyncLogMap).toList()
        ));
    }

    // ── Import CSV ────────────────────────────────────────────────────────────

    @PostMapping("/import/{listName}")
    @Operation(summary = "Importer une liste depuis un CSV",
               description = """
               Importe des entrées de sanctions depuis un contenu CSV pour une liste manuelle.

               **Format CSV** (sans entête, une entité par ligne) :
               ```
               # Commentaire (ligne ignorée)
               entityName,aliases,countryCode,entityType
               "KONAN AMARA","AMARA KONAN|K. AMARA",CI,INDIVIDUAL
               "SOCIÉTÉ FANTÔME",,NG,ENTITY
               ```

               - `aliases` : plusieurs valeurs séparées par `|`
               - `countryCode` : code ISO 3166-1 alpha-2 (optionnel)
               - `entityType` : `INDIVIDUAL | ENTITY | VESSEL | AIRCRAFT` (optionnel)

               Les listes OFAC_SDN, UN_CONSOLIDATED et EU_CONSOLIDATED peuvent aussi
               être importées manuellement via cet endpoint (remplace la source officielle).
               """)
    public ResponseEntity<Map<String, Object>> importCsv(
            @PathVariable @Parameter(description = "Nom de la liste cible") String listName,
            @RequestBody Map<String, String> body) {
        validateListName(listName);
        String csvContent = body.get("content");
        if (csvContent == null || csvContent.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "Le champ 'content' est obligatoire."
            ));
        }
        log.info("Import CSV manuel : liste={}, taille={}c", listName, csvContent.length());
        SanctionsSyncLog result = syncService.importCsv(listName, csvContent);
        return ResponseEntity.ok(Map.of("success", true, "data", toSyncLogMap(result)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateListName(String listName) {
        if (!VALID_LIST_NAMES.contains(listName)) {
            throw new IllegalArgumentException(
                "Nom de liste invalide : " + listName + ". Valeurs acceptées : " + VALID_LIST_NAMES);
        }
    }

    private Map<String, Object> toSyncLogMap(SanctionsSyncLog log) {
        return Map.of(
            "id",              log.getId(),
            "listName",        log.getListName(),
            "syncedAt",        log.getSyncedAt(),
            "status",          log.getStatus(),
            "entriesImported", log.getEntriesImported(),
            "errorMessage",    log.getErrorMessage() != null ? log.getErrorMessage() : "",
            "durationMs",      log.getDurationMs()   != null ? log.getDurationMs()   : 0L
        );
    }
}
