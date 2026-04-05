package com.ebithex.reconciliation;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.payment.api.ReconciliationController;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Masquage PII numéros de téléphone dans la réconciliation.
 *
 * Couvre :
 *  - Rôle RECONCILIATION → numéro masqué (ex : +22507****56)
 *  - Rôle FINANCE        → numéro masqué
 *  - Rôle ADMIN          → numéro complet affiché
 *  - Rôle SUPER_ADMIN    → numéro complet affiché
 *  - Logique statique maskPhone() — cas limites
 */
@DisplayName("Réconciliation PII — Masquage numéro de téléphone par rôle")
class ReconciliationPiiTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory factory;

    private static final String PHONE = "+225071234567";

    @BeforeEach
    void setUp() {
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-REF-" + System.nanoTime(), null, "En attente"));
        TestDataFactory.MerchantCredentials m = factory.registerKycVerifiedMerchant();
        // Créer une transaction avec ce numéro
        restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "amount",            new java.math.BigDecimal("5000"),
                "phoneNumber",       PHONE,
                "merchantReference", "PII-" + System.nanoTime()
            ), factory.apiKeyHeaders(m.apiKey())),
            Map.class);
    }

    @Test
    @DisplayName("RECONCILIATION → numéro masqué dans la réponse")
    void reconciliation_phoneIsMasked() throws Exception {
        mockMvc.perform(
            get("/api/internal/reconciliation/transactions?sort=createdAt,desc")
                .with(user(factory.reconciliationPrincipal()))
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.content[0].phoneNumber").value(
             org.hamcrest.Matchers.not(PHONE)))
         .andExpect(jsonPath("$.data.content[0].phoneNumber").value(
             org.hamcrest.Matchers.containsString("*")));
    }

    @Test
    @DisplayName("FINANCE → numéro masqué dans la réponse")
    void finance_phoneIsMasked() throws Exception {
        mockMvc.perform(
            get("/api/internal/reconciliation/transactions?sort=createdAt,desc")
                .with(user(factory.financePrincipal()))
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.content[0].phoneNumber").value(
             org.hamcrest.Matchers.containsString("*")));
    }

    @Test
    @DisplayName("ADMIN → numéro complet visible")
    void admin_phoneIsNotMasked() throws Exception {
        mockMvc.perform(
            get("/api/internal/reconciliation/transactions?sort=createdAt,desc")
                .with(user(factory.adminPrincipal()))
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.content[0].phoneNumber").value(PHONE));
    }

    @Test
    @DisplayName("SUPER_ADMIN → numéro complet visible")
    void superAdmin_phoneIsNotMasked() throws Exception {
        mockMvc.perform(
            get("/api/internal/reconciliation/transactions?sort=createdAt,desc")
                .with(user(factory.superAdminPrincipal()))
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.content[0].phoneNumber").value(PHONE));
    }

    // ── Tests unitaires de la méthode statique maskPhone ─────────────────────

    @Test
    @DisplayName("maskPhone — numéro standard masqué correctement")
    void maskPhone_standardNumber() {
        String masked = ReconciliationController.maskPhone("+22507123456");
        assertThat(masked).startsWith("+225");
        assertThat(masked).endsWith("56");
        assertThat(masked).contains("*");
        assertThat(masked).doesNotContain("123");
    }

    @Test
    @DisplayName("maskPhone — null retourne null")
    void maskPhone_null() {
        assertThat(ReconciliationController.maskPhone(null)).isNull();
    }

    @Test
    @DisplayName("maskPhone — chaîne trop courte retournée telle quelle")
    void maskPhone_tooShort() {
        String input = "+225";
        assertThat(ReconciliationController.maskPhone(input)).isEqualTo(input);
    }
}