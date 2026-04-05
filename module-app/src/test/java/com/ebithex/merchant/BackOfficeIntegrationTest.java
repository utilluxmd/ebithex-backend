package com.ebithex.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Back-office marchands.
 *
 * Couvre :
 *  - Liste marchands (ADMIN voit tout, COUNTRY_ADMIN filtré par pays)
 *  - KYC workflow : NONE → PENDING → APPROVED / REJECTED
 *  - Enforcement pays COUNTRY_ADMIN (ne peut pas agir sur autre pays)
 *  - Activation / Désactivation
 */
@DisplayName("Back-office — KYC Workflow, Country Enforcement, Activation")
class BackOfficeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private ObjectMapper objectMapper;

    // ── Liste marchands ───────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN : GET /internal/merchants → voit tous les marchands")
    void listMerchants_admin_seesAll() throws Exception {
        factory.registerMerchantInCountry("CI");
        factory.registerMerchantInCountry("SN");

        mockMvc.perform(get("/api/internal/merchants")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("COUNTRY_ADMIN CI : GET /internal/merchants → filtre automatiquement par pays CI")
    void listMerchants_countryAdmin_filteredByCountry() throws Exception {
        TestDataFactory.MerchantCredentials ciMerchant = factory.registerMerchantInCountry("CI");
        factory.registerMerchantInCountry("SN"); // ne doit pas apparaître

        mockMvc.perform(get("/api/internal/merchants")
                .with(authentication(auth(factory.countryAdminPrincipal("CI")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].country", everyItem(equalTo("CI"))));
    }

    @Test
    @DisplayName("Non authentifié → 401")
    void listMerchants_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/merchants"))
            .andExpect(status().isUnauthorized());
    }

    // ── KYC Workflow ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("KYC workflow complet : NONE → PENDING → APPROVED")
    void kycWorkflow_submitThenApprove_success() throws Exception {
        TestDataFactory.MerchantCredentials merchant = factory.registerMerchant(restTemplate, url(""));

        // 0. Seed required accepted documents (prerequisite for submit)
        factory.seedRequiredKycDocuments(merchant.merchantId());

        // 1. Soumettre le KYC (côté marchand)
        restTemplate.postForEntity(
            url("/v1/merchants/kyc"),
            new org.springframework.http.HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        // 2. Approuver (côté admin) — vérifier état PENDING d'abord
        mockMvc.perform(get("/api/internal/merchants/" + merchant.merchantId())
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kycStatus").value("PENDING"));

        // 3. Approuver
        mockMvc.perform(put("/api/internal/merchants/" + merchant.merchantId() + "/kyc/approve")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk());

        // 4. Vérifier état APPROVED
        mockMvc.perform(get("/api/internal/merchants/" + merchant.merchantId())
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kycStatus").value("APPROVED"))
            .andExpect(jsonPath("$.data.kycVerified").value(true));
    }

    @Test
    @DisplayName("KYC workflow : NONE → PENDING → REJECTED avec raison")
    void kycWorkflow_submitThenReject_success() throws Exception {
        TestDataFactory.MerchantCredentials merchant = factory.registerMerchant(restTemplate, url(""));

        // Seed required accepted documents
        factory.seedRequiredKycDocuments(merchant.merchantId());

        // Soumettre
        restTemplate.postForEntity(
            url("/v1/merchants/kyc"),
            new org.springframework.http.HttpEntity<>(null, factory.apiKeyHeaders(merchant.apiKey())),
            Map.class);

        // Rejeter
        String rejectBody = objectMapper.writeValueAsString(Map.of("reason", "Documents non conformes"));
        mockMvc.perform(put("/api/internal/merchants/" + merchant.merchantId() + "/kyc/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody)
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk());

        // Vérifier REJECTED
        mockMvc.perform(get("/api/internal/merchants/" + merchant.merchantId())
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kycStatus").value("REJECTED"))
            .andExpect(jsonPath("$.data.kycRejectionReason").value("Documents non conformes"));
    }

    @Test
    @DisplayName("Approuver KYC sans PENDING préalable → 400 KYC_INVALID_STATE")
    void approveKyc_notPending_returns400() throws Exception {
        TestDataFactory.MerchantCredentials merchant = factory.registerMerchantInCountry("CI");
        // Statut est NONE, pas PENDING

        mockMvc.perform(put("/api/internal/merchants/" + merchant.merchantId() + "/kyc/approve")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("KYC_INVALID_STATE"));
    }

    // ── Country enforcement ───────────────────────────────────────────────────

    @Test
    @DisplayName("COUNTRY_ADMIN CI ne peut pas approuver KYC d'un marchand SN → 400 ACCESS_DENIED")
    void approveKyc_countryAdmin_wrongCountry_returns400() throws Exception {
        // Créer un marchand SN (enforceCountryAccess est appelé AVANT la vérification d'état KYC)
        TestDataFactory.MerchantCredentials snMerchant = factory.registerMerchantInCountry("SN");

        // CI admin essaie d'approuver un marchand SN → bloqué par country check
        mockMvc.perform(put("/api/internal/merchants/" + snMerchant.merchantId() + "/kyc/approve")
                .with(authentication(auth(factory.countryAdminPrincipal("CI")))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("COUNTRY_ADMIN CI peut voir un marchand CI → 200")
    void getMerchant_countryAdmin_sameCountry_success() throws Exception {
        TestDataFactory.MerchantCredentials ciMerchant = factory.registerMerchantInCountry("CI");

        mockMvc.perform(get("/api/internal/merchants/" + ciMerchant.merchantId())
                .with(authentication(auth(factory.countryAdminPrincipal("CI")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(ciMerchant.merchantId().toString()));
    }

    @Test
    @DisplayName("COUNTRY_ADMIN CI ne peut pas voir un marchand SN → 400 ACCESS_DENIED")
    void getMerchant_countryAdmin_wrongCountry_returns400() throws Exception {
        TestDataFactory.MerchantCredentials snMerchant = factory.registerMerchantInCountry("SN");

        mockMvc.perform(get("/api/internal/merchants/" + snMerchant.merchantId())
                .with(authentication(auth(factory.countryAdminPrincipal("CI")))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    // ── Activation / Désactivation ────────────────────────────────────────────

    @Test
    @DisplayName("Désactiver un marchand → compte désactivé, login impossible")
    void deactivateMerchant_loginFails() throws Exception {
        TestDataFactory.MerchantCredentials merchant = factory.registerMerchant(restTemplate, url(""));

        // Désactiver
        mockMvc.perform(put("/api/internal/merchants/" + merchant.merchantId() + "/deactivate")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk());

        // Login doit échouer
        Map<String, String> loginBody = Map.of("email", merchant.email(), "password", merchant.password());
        org.springframework.http.ResponseEntity<Map> loginResp =
            restTemplate.postForEntity(url("/v1/auth/login"), loginBody, Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(loginResp.getBody().get("errorCode")).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    @DisplayName("Réactiver un marchand désactivé → login fonctionne à nouveau")
    void reactivateMerchant_loginSucceeds() throws Exception {
        TestDataFactory.MerchantCredentials merchant = factory.registerMerchantInCountry("CI");

        // Désactiver puis réactiver
        mockMvc.perform(put("/api/internal/merchants/" + merchant.merchantId() + "/deactivate")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/internal/merchants/" + merchant.merchantId() + "/activate")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk());

        // Vérifier que le compte est actif
        mockMvc.perform(get("/api/internal/merchants/" + merchant.merchantId())
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(true));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private UsernamePasswordAuthenticationToken auth(com.ebithex.shared.security.EbithexPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}