package com.ebithex.sanctions;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.sanctions.application.FuzzyNameMatcher;
import com.ebithex.sanctions.application.SanctionsScreeningService;
import com.ebithex.sanctions.domain.SanctionsEntry;
import com.ebithex.sanctions.infrastructure.SanctionsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration — Screening de sanctions.
 *
 * Couvre :
 *  - Détection des pays à haut risque
 *  - Non-détection des pays autorisés
 *  - Correspondance exacte (score 1.0 → blocage)
 *  - Correspondance floue near-miss (score ∈ [0.80, 0.92[ → révision, pas de blocage)
 *  - Translittérations reconnues (KADHAFI / GADDAFI)
 *  - Non-correspondance pour un nom légitime
 *  - Entrée inactive ignorée
 *  - FuzzyNameMatcher : normalisation des diacritiques et de la ponctuation
 *  - API back-office accessible par COMPLIANCE
 */
@DisplayName("Sanctions — Screening pays à haut risque, fuzzy matching et listes")
class SanctionsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory            factory;
    @Autowired private SanctionsRepository        sanctionsRepository;
    @Autowired private SanctionsScreeningService  screeningService;

    private SanctionsEntry testEntry;

    @BeforeEach
    void setUp() {
        factory.registerMerchant(restTemplate, url(""));
    }

    @AfterEach
    void tearDown() {
        // Nettoyage des entrées de test pour éviter l'interférence entre tests
        if (testEntry != null && testEntry.getId() != null) {
            sanctionsRepository.deleteById(testEntry.getId());
            testEntry = null;
        }
    }

    // ── Pays à haut risque ────────────────────────────────────────────────────

    @Test
    @DisplayName("Pays à haut risque (IR) → détecté")
    void highRiskCountry_isDetected() {
        assertThat(screeningService.isHighRiskCountry("IR")).isTrue();
    }

    @Test
    @DisplayName("Pays à haut risque (KP) → détecté")
    void highRiskCountry_kp_isDetected() {
        assertThat(screeningService.isHighRiskCountry("KP")).isTrue();
    }

    @Test
    @DisplayName("Pays autorisé (CI) → non détecté")
    void nonHighRiskCountry_ci_notDetected() {
        assertThat(screeningService.isHighRiskCountry("CI")).isFalse();
    }

    @Test
    @DisplayName("Pays autorisé (KE) → non détecté")
    void nonHighRiskCountry_ke_notDetected() {
        assertThat(screeningService.isHighRiskCountry("KE")).isFalse();
    }

    // ── Screening par nom — correspondance exacte ─────────────────────────────

    @Test
    @DisplayName("Correspondance exacte → hit + requiresBlock=true (score=1.0)")
    void exactMatch_isBlockHit() {
        testEntry = sanctionsRepository.save(SanctionsEntry.builder()
            .listName("OFAC_SDN")
            .entityName("ACME SANCTIONS TEST CORP")
            .isActive(true)
            .build());

        SanctionsScreeningService.SanctionsCheckResult result =
            screeningService.checkName("ACME SANCTIONS TEST CORP");

        assertThat(result.hit()).isTrue();
        assertThat(result.requiresBlock()).isTrue();
        assertThat(result.score()).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("Alias exact → hit détecté")
    void exactAlias_isHit() {
        testEntry = sanctionsRepository.save(SanctionsEntry.builder()
            .listName("UN_CONSOLIDATED")
            .entityName("SANCTIONED ENTITY PRIMARY")
            .aliases("ALIAS TEST NAME, AUTRE ALIAS")
            .isActive(true)
            .build());

        assertThat(screeningService.isNameSanctioned("ALIAS TEST NAME")).isTrue();
    }

    // ── Screening par nom — fuzzy (near-miss) ─────────────────────────────────

    @Test
    @DisplayName("Translittération (KADHAFI / GADDAFI) → near-miss détecté, pas de blocage")
    void transliteration_isNearMiss_notBlock() {
        testEntry = sanctionsRepository.save(SanctionsEntry.builder()
            .listName("OFAC_SDN")
            .entityName("MUAMMAR KADHAFI")
            .isActive(true)
            .build());

        SanctionsScreeningService.SanctionsCheckResult result =
            screeningService.checkName("MUAMMAR GADDAFI");

        // Score Jaro-Winkler ≈ 0.947 → dans la zone [0.80, 0.95[ → near-miss
        assertThat(result.hit()).isTrue();
        assertThat(result.requiresBlock()).isFalse();  // near-miss : pas de blocage
        assertThat(result.score()).isBetween(0.80, 0.95);
    }

    @Test
    @DisplayName("Variante orthographique proche (OSAMA / USAMA) → blocage (score ≥ 0.95)")
    void closeName_isBlockHit() {
        testEntry = sanctionsRepository.save(SanctionsEntry.builder()
            .listName("OFAC_SDN")
            .entityName("OSAMA BIN LADEN")
            .isActive(true)
            .build());

        SanctionsScreeningService.SanctionsCheckResult result =
            screeningService.checkName("USAMA BIN LADEN");

        // OSAMA / USAMA + "BIN LADEN" commun → score très élevé ≥ 0.95
        assertThat(result.hit()).isTrue();
        assertThat(result.requiresBlock()).isTrue();
        assertThat(result.score()).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    @DisplayName("Diacritiques (Kâdhâfî) normalisés → correspondance détectée")
    void diacritics_areNormalized_hit() {
        testEntry = sanctionsRepository.save(SanctionsEntry.builder()
            .listName("CUSTOM")
            .entityName("MUAMMAR KADHAFI")
            .isActive(true)
            .build());

        // Même personne, nom avec diacritiques
        assertThat(screeningService.isNameSanctioned("Muammar Kâdhâfî")).isTrue();
    }

    @Test
    @DisplayName("Nom légitime court → aucune correspondance")
    void legitimateName_noHit() {
        assertThat(screeningService.isNameSanctioned("Kouassi Jean-Baptiste")).isFalse();
    }

    @Test
    @DisplayName("Entrée inactive → non retournée dans le screening")
    void inactiveEntry_notMatched() {
        testEntry = sanctionsRepository.save(SanctionsEntry.builder()
            .listName("CUSTOM")
            .entityName("INACTIVE SANCTIONS ENTITY TEST")
            .isActive(false)
            .build());

        assertThat(screeningService.isNameSanctioned("INACTIVE SANCTIONS ENTITY TEST")).isFalse();
    }

    // ── FuzzyNameMatcher — normalisation ─────────────────────────────────────

    @Test
    @DisplayName("FuzzyNameMatcher — diacritiques supprimés à la normalisation")
    void fuzzyMatcher_normalizeDiacritics() {
        assertThat(FuzzyNameMatcher.normalize("Côte d'Ivoire")).isEqualTo("COTE D IVOIRE");
        assertThat(FuzzyNameMatcher.normalize("Müller")).isEqualTo("MULLER");
        assertThat(FuzzyNameMatcher.normalize("al-Qaïda")).isEqualTo("AL QAIDA");
    }

    @Test
    @DisplayName("FuzzyNameMatcher — score exact = 1.0")
    void fuzzyMatcher_exactScore() {
        double score = FuzzyNameMatcher.bestScore("OSAMA BIN LADEN",
            java.util.List.of("OSAMA BIN LADEN"));
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("FuzzyNameMatcher — chaînes sans rapport → score < 0.65")
    void fuzzyMatcher_unrelatedStrings_lowScore() {
        double score = FuzzyNameMatcher.bestScore("KOUASSI JEAN",
            java.util.List.of("OSAMA BIN LADEN"));
        assertThat(score).isLessThan(0.65);
    }

    // ── API back-office ───────────────────────────────────────────────────────

    @Test
    @DisplayName("API /internal/sanctions/entries — accessible par COMPLIANCE")
    void backOffice_listEntries_complianceRole() throws Exception {
        mockMvc.perform(get("/api/internal/sanctions/entries")
                .with(SecurityMockMvcRequestPostProcessors.user("compliance@ebithex.io")
                    .roles("COMPLIANCE")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("API /internal/sanctions/entries — MERCHANT reçoit 403")
    void backOffice_listEntries_merchantForbidden() throws Exception {
        TestDataFactory.MerchantCredentials merchant = factory.registerKycVerifiedMerchant();
        mockMvc.perform(get("/api/internal/sanctions/entries")
                .header("X-API-Key", merchant.apiKey()))
            .andExpect(status().isForbidden());
    }
}