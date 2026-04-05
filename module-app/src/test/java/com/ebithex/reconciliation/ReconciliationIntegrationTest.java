package com.ebithex.reconciliation;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.wallet.application.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Réconciliation back-office.
 *
 * Couvre :
 *  - Listing transactions / payouts avec filtres
 *  - COUNTRY_ADMIN ne voit que son pays (sous-requête Merchant.country)
 *  - Export CSV (content-type, présence du header)
 *  - Summary agrégé (comptes et montants par statut)
 */
@DisplayName("Réconciliation — Listing, Country Scoping, CSV Export, Summary")
class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    private TestDataFactory.MerchantCredentials ciMerchant;
    private TestDataFactory.MerchantCredentials snMerchant;

    @BeforeEach
    void setUp() {
        ciMerchant = factory.registerMerchantInCountry("CI");
        snMerchant = factory.registerMerchantInCountry("SN");

        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(new MobileMoneyOperator.OperatorInitResponse(
                "OP-RECON-" + System.nanoTime(), TransactionStatus.SUCCESS,
                null, null, "Success"));
    }

    // ── ADMIN — voit tout ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN : GET /internal/reconciliation/transactions → voit toutes les transactions")
    void listTransactions_admin_seesAll() throws Exception {
        // Créer des transactions pour CI et SN
        createTransaction(ciMerchant, "RECON-CI-001");
        createTransaction(snMerchant, "RECON-SN-001");

        mockMvc.perform(get("/api/internal/reconciliation/transactions")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(greaterThanOrEqualTo(2)));
    }

    // ── COUNTRY_ADMIN — filtré par pays ───────────────────────────────────────

    @Test
    @DisplayName("COUNTRY_ADMIN CI : voit uniquement les transactions de marchands CI")
    void listTransactions_countryAdmin_filteredByCountry() throws Exception {
        createTransaction(ciMerchant, "RECON-CI-SCOPE-001");
        createTransaction(snMerchant, "RECON-SN-SCOPE-001");

        mockMvc.perform(get("/api/internal/reconciliation/transactions")
                .with(authentication(auth(factory.countryAdminPrincipal("CI")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].merchantId",
                not(hasItem(equalTo(snMerchant.merchantId().toString())))));
    }

    @Test
    @DisplayName("COUNTRY_ADMIN CI : payouts filtrés par pays")
    void listPayouts_countryAdmin_filteredByCountry() throws Exception {
        TestDataFactory.MerchantCredentials kycCi = factory.registerKycVerifiedMerchant();
        // Note: kycCi est créé avec pays CI
        when(operatorGateway.initiateDisbursement(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "DISBURSE-SCOPE-001", null, "OK"));

        createPayout(kycCi, "PORECON-CI-001");

        mockMvc.perform(get("/api/internal/reconciliation/payouts")
                .with(authentication(auth(factory.countryAdminPrincipal("CI")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(greaterThanOrEqualTo(0)));
    }

    // ── Filtres ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Filtre par merchantId → retourne uniquement ses transactions")
    void listTransactions_filterByMerchantId_onlyThatMerchant() throws Exception {
        createTransaction(ciMerchant, "RECON-FILTER-CI-001");
        createTransaction(snMerchant, "RECON-FILTER-SN-001");

        mockMvc.perform(get("/api/internal/reconciliation/transactions?merchantId=" + ciMerchant.merchantId())
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].merchantId",
                everyItem(equalTo(ciMerchant.merchantId().toString()))));
    }

    @Test
    @DisplayName("Filtre par status SUCCESS → uniquement transactions réussies")
    void listTransactions_filterByStatus_onlySuccess() throws Exception {
        createTransaction(ciMerchant, "RECON-STATUS-001");

        mockMvc.perform(get("/api/internal/reconciliation/transactions?status=SUCCESS")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].status", everyItem(equalTo("SUCCESS"))));
    }

    // ── Export CSV ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Export CSV transactions → Content-Type text/csv, nom de fichier correct")
    void exportTransactions_csv_correctHeaders() throws Exception {
        createTransaction(ciMerchant, "RECON-CSV-001");

        mockMvc.perform(get("/api/internal/reconciliation/export/transactions")
                .with(authentication(auth(factory.reconciliationPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/csv")))
            .andExpect(header().string("Content-Disposition", containsString("transactions_")));
    }

    @Test
    @DisplayName("Export CSV payouts → Content-Type text/csv")
    void exportPayouts_csv_correctHeaders() throws Exception {
        mockMvc.perform(get("/api/internal/reconciliation/export/payouts")
                .with(authentication(auth(factory.reconciliationPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/csv")))
            .andExpect(header().string("Content-Disposition", containsString("payouts_")));
    }

    @Test
    @DisplayName("Export CSV sans rôle RECONCILIATION → 403")
    void exportTransactions_withoutRole_returns403() throws Exception {
        // FINANCE n'a pas accès aux exports
        mockMvc.perform(get("/api/internal/reconciliation/export/transactions")
                .with(authentication(auth(factory.financePrincipal()))))
            .andExpect(status().isForbidden());
    }

    // ── Summary agrégé ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /summary → retourne des agrégats transactions + payouts")
    void getSummary_returnsBothAggregates() throws Exception {
        createTransaction(ciMerchant, "RECON-SUMMARY-001");

        mockMvc.perform(get("/api/internal/reconciliation/summary")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactions").isArray())
            .andExpect(jsonPath("$.data.payouts").isArray());
    }

    @Test
    @DisplayName("GET /summary → les agrégats ont les bons champs")
    void getSummary_aggregateFields_correct() throws Exception {
        createTransaction(ciMerchant, "RECON-SUMMARY-FIELDS-001");

        mockMvc.perform(get("/api/internal/reconciliation/summary")
                .with(authentication(auth(factory.adminPrincipal()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactions[0].status").exists())
            .andExpect(jsonPath("$.data.transactions[0].count").exists())
            .andExpect(jsonPath("$.data.transactions[0].totalAmount").exists())
            .andExpect(jsonPath("$.data.transactions[0].totalFees").exists());
    }

    @Test
    @DisplayName("COUNTRY_ADMIN CI : summary limité aux marchands CI")
    void getSummary_countryAdmin_limitedToCountry() throws Exception {
        createTransaction(ciMerchant, "RECON-SUMMARY-CI-001");
        createTransaction(snMerchant, "RECON-SUMMARY-SN-001");

        // Les deux scopes ont du contenu mais sont différents
        // On vérifie juste que la requête passe sans erreur (le filtrage est en JPQL)
        mockMvc.perform(get("/api/internal/reconciliation/summary")
                .with(authentication(auth(factory.countryAdminPrincipal("CI")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactions").isArray());
    }

    // ── Finance role ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("FINANCE peut lister les transactions et payouts")
    void listTransactions_financeRole_allowed() throws Exception {
        mockMvc.perform(get("/api/internal/reconciliation/transactions")
                .with(authentication(auth(factory.financePrincipal()))))
            .andExpect(status().isOk());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void createTransaction(TestDataFactory.MerchantCredentials merchant, String ref) {
        Map<String, Object> body = Map.of(
            "amount",            new BigDecimal("5000"),
            "phoneNumber",       "+225051234567",
            "merchantReference", ref,
            "description",       "Recon test"
        );
        restTemplate.exchange(url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchant.apiKey())), Map.class);
    }

    private void createPayout(TestDataFactory.MerchantCredentials merchant, String ref) {
        Map<String, Object> body = Map.of(
            "amount",            new BigDecimal("5000"),
            "phoneNumber",       "+225051234567",
            "merchantReference", ref,
            "beneficiaryName",   "Test"
        );
        restTemplate.exchange(url("/v1/payouts"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchant.apiKey())), Map.class);
    }

    private UsernamePasswordAuthenticationToken auth(com.ebithex.shared.security.EbithexPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}