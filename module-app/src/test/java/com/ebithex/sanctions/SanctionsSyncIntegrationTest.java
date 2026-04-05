package com.ebithex.sanctions;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.sanctions.application.SanctionsListSyncService;
import com.ebithex.sanctions.domain.SanctionsEntry;
import com.ebithex.sanctions.domain.SanctionsSyncLog;
import com.ebithex.sanctions.infrastructure.SanctionsRepository;
import com.ebithex.sanctions.infrastructure.SanctionsSyncLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.*;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Synchronisation et import des listes de sanctions.
 *
 * Couvre :
 *  - Import CSV : entrées insérées en base, log SUCCESS créé
 *  - Import CSV avec commentaires/lignes vides ignorés
 *  - Import CSV avec aliases pipe-séparés
 *  - Import CSV remplace les anciennes entrées d'une liste
 *  - Import CSV contenu vide → log PARTIAL
 *  - Sync manuelle via API (stub HTTP) → log SUCCESS
 *  - Sync liste manuelle (ECOWAS_LOCAL) → 400 (utiliser /import)
 *  - Sync globale avec échec HTTP → log FAILED créé
 *  - GET /sync/status → dernier log par liste
 *  - GET /sync/history → logs récents
 *  - Contrôle d'accès : COMPLIANCE autorisé, MERCHANT refusé (403)
 */
@DisplayName("Sanctions — Synchronisation et import des listes")
class SanctionsSyncIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory            factory;
    @Autowired private SanctionsRepository        sanctionsRepository;
    @Autowired private SanctionsSyncLogRepository syncLogRepository;

    @MockitoSpyBean
    private SanctionsListSyncService syncService;

    @AfterEach
    void tearDown() {
        sanctionsRepository.deleteByListName("CUSTOM");
        sanctionsRepository.deleteByListName("ECOWAS_LOCAL");
        sanctionsRepository.deleteByListName("OFAC_SDN");
        syncLogRepository.deleteAll();
    }

    // ── Import CSV — service direct ───────────────────────────────────────────

    @Test
    @DisplayName("importCsv → entrées insérées en base, log SUCCESS")
    void importCsv_insertsEntries_andLogsSuccess() {
        String csv = """
            # Ligne de commentaire ignorée
            "ENTITE TEST ALPHA","ALIAS A|ALIAS B",CI,ENTITY
            "INDIVIDU BETA",,NG,INDIVIDUAL
            """;

        SanctionsSyncLog result = syncService.importCsv("CUSTOM", csv);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getEntriesImported()).isEqualTo(2);

        List<SanctionsEntry> entries = sanctionsRepository.findByIsActiveTrue()
            .stream().filter(e -> "CUSTOM".equals(e.getListName())).toList();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(SanctionsEntry::getEntityName))
            .containsExactlyInAnyOrder("ENTITE TEST ALPHA", "INDIVIDU BETA");
    }

    @Test
    @DisplayName("importCsv → aliases pipe-séparés convertis en virgules")
    void importCsv_pipeAliases_convertedCorrectly() {
        syncService.importCsv("CUSTOM", "\"ALIAS CORP\",\"ALPHA|BETA|GAMMA\",CI,ENTITY\n");

        SanctionsEntry entry = sanctionsRepository.findByIsActiveTrue()
            .stream().filter(e -> "ALIAS CORP".equals(e.getEntityName()))
            .findFirst().orElseThrow();
        assertThat(entry.getAliases()).contains("ALPHA").contains("BETA").contains("GAMMA");
    }

    @Test
    @DisplayName("importCsv → remplace les anciennes entrées de la liste")
    void importCsv_replacesExistingEntries() {
        syncService.importCsv("ECOWAS_LOCAL", "\"ENTITE ANCIENNE\",,CI,ENTITY\n");
        assertThat(sanctionsRepository.countByListNameAndIsActiveTrue("ECOWAS_LOCAL")).isEqualTo(1);

        syncService.importCsv("ECOWAS_LOCAL",
            "\"ENTITE NOUVELLE 1\",,NG,ENTITY\n\"ENTITE NOUVELLE 2\",,GH,INDIVIDUAL\n");

        List<SanctionsEntry> entries = sanctionsRepository.findByIsActiveTrue()
            .stream().filter(e -> "ECOWAS_LOCAL".equals(e.getListName())).toList();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(SanctionsEntry::getEntityName))
            .doesNotContain("ENTITE ANCIENNE")
            .containsExactlyInAnyOrder("ENTITE NOUVELLE 1", "ENTITE NOUVELLE 2");
    }

    @Test
    @DisplayName("importCsv contenu vide → log PARTIAL")
    void importCsv_emptyContent_logsPartial() {
        SanctionsSyncLog result = syncService.importCsv("CUSTOM", "# Commentaires seulement\n\n");

        assertThat(result.getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getEntriesImported()).isEqualTo(0);
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    // ── Sync manuelle via API back-office ─────────────────────────────────────

    @Test
    @DisplayName("POST /sync/OFAC_SDN → mock HTTP → log SUCCESS retourné")
    void syncList_viaApi_returnsLog() throws Exception {
        String minimalOfacXml = """
            <?xml version="1.0"?>
            <sdnList>
              <sdnEntry>
                <lastName>SYNC TEST CORP</lastName>
                <sdnType>Entity</sdnType>
              </sdnEntry>
            </sdnList>
            """;
        doReturn(minimalOfacXml.getBytes()).when(syncService).downloadContent(anyString());

        mockMvc.perform(post("/api/internal/sanctions/sync/OFAC_SDN")
                .with(SecurityMockMvcRequestPostProcessors.user("compliance@ebithex.io")
                    .roles("COMPLIANCE")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.listName").value("OFAC_SDN"))
            .andExpect(jsonPath("$.data.entriesImported").value(1));
    }

    @Test
    @DisplayName("POST /sync/ECOWAS_LOCAL → 400 (liste manuelle — utiliser /import)")
    void syncList_manualList_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/internal/sanctions/sync/ECOWAS_LOCAL")
                .with(SecurityMockMvcRequestPostProcessors.user("compliance@ebithex.io")
                    .roles("COMPLIANCE")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /sync → échec HTTP → log FAILED créé pour chaque liste")
    void syncAll_httpFailure_logsFailureForEachList() throws Exception {
        doThrow(new java.io.IOException("Connexion refusée")).when(syncService).downloadContent(anyString());

        mockMvc.perform(post("/api/internal/sanctions/sync")
                .with(SecurityMockMvcRequestPostProcessors.user("compliance@ebithex.io")
                    .roles("COMPLIANCE")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].status").value("FAILED"))
            .andExpect(jsonPath("$.data[0].errorMessage").isNotEmpty())
            .andExpect(jsonPath("$.data.length()").value(3)); // OFAC, ONU, UE
    }

    // ── Import CSV via API ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /import/CUSTOM → entrées créées, log SUCCESS dans la réponse")
    void importCsv_viaApi_returnsSuccess() throws Exception {
        String body = """
            {
              "content": "\\"IMPORTED ENTITY\\",,CI,ENTITY\\n"
            }
            """;

        mockMvc.perform(post("/api/internal/sanctions/import/CUSTOM")
                .contentType(APPLICATION_JSON)
                .content(body)
                .with(SecurityMockMvcRequestPostProcessors.user("compliance@ebithex.io")
                    .roles("COMPLIANCE")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.entriesImported").value(1));
    }

    @Test
    @DisplayName("POST /import/CUSTOM — body sans 'content' → 400")
    void importCsv_viaApi_missingContent_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/internal/sanctions/import/CUSTOM")
                .contentType(APPLICATION_JSON)
                .content("{}")
                .with(SecurityMockMvcRequestPostProcessors.user("admin@ebithex.io")
                    .roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    // ── Statut et historique ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /sync/status → contient le dernier log après un import")
    void syncStatus_returnsLastLogPerList() throws Exception {
        syncService.importCsv("CUSTOM", "\"STATUS ENTITY\",,CI,ENTITY\n");

        mockMvc.perform(get("/api/internal/sanctions/sync/status")
                .with(SecurityMockMvcRequestPostProcessors.user("admin@ebithex.io")
                    .roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.CUSTOM").exists())
            .andExpect(jsonPath("$.data.CUSTOM.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.CUSTOM.entriesImported").value(1));
    }

    @Test
    @DisplayName("GET /sync/history → retourne les logs récents")
    void syncHistory_returnsRecentLogs() throws Exception {
        syncService.importCsv("CUSTOM", "\"HISTORY ENTITY 1\",,CI,ENTITY\n");
        syncService.importCsv("CUSTOM", "\"HISTORY ENTITY 2\",,NG,ENTITY\n");

        mockMvc.perform(get("/api/internal/sanctions/sync/history?limit=10")
                .with(SecurityMockMvcRequestPostProcessors.user("admin@ebithex.io")
                    .roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)));
    }

    // ── Contrôle d'accès ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /import/CUSTOM — MERCHANT → 403")
    void importCsv_merchantRole_isForbidden() {
        TestDataFactory.MerchantCredentials merchant = factory.registerKycVerifiedMerchant();

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/sanctions/import/CUSTOM"), HttpMethod.POST,
            new HttpEntity<>(Map.of("content", "\"TEST\",,CI,ENTITY"),
                factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /sync/status — MERCHANT → 403")
    void syncStatus_merchantRole_isForbidden() {
        TestDataFactory.MerchantCredentials merchant = factory.registerKycVerifiedMerchant();

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/sanctions/sync/status"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
