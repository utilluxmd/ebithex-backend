package com.ebithex.aml;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.aml.application.AmlScreeningService;
import com.ebithex.aml.domain.AmlAlert;
import com.ebithex.aml.domain.AmlStatus;
import com.ebithex.aml.infrastructure.AmlAlertRepository;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.shared.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration AML.
 *
 * Couvre :
 *  - Screening AML sur initiation de paiement (test mode ignoré)
 *  - API back-office : liste des alertes, review (CLEARED / REPORTED)
 */
@DisplayName("AML — Screening, Alertes, Review")
class AmlIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private AmlAlertRepository amlAlertRepository;

    @Autowired
    private AmlScreeningService screeningService;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerMerchant(restTemplate, url(""));

        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-AML-001", null, "USSD envoyé"));
    }

    @Test
    @DisplayName("Paiement en mode test → aucune alerte AML créée")
    void testModePayment_noAmlAlert() {
        // Le marchand enregistré via l'API est en test mode par défaut
        Map<String, Object> body = Map.of(
            "merchantReference", "AML-001",
            "amount", "5000",
            "currency", "XOF",
            "phoneNumber", "+22505" + System.nanoTime() % 10000000,
            "operator", "MTN_MOMO_CI"
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, factory.apiKeyHeaders(merchant.apiKey()));
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // En test mode, aucune alerte ne doit être créée
        long alertCount = amlAlertRepository.countByMerchantIdAndCreatedAtAfter(
            merchant.merchantId(), java.time.LocalDateTime.now().minusMinutes(1));
        assertThat(alertCount).isZero();
    }

    @Test
    @DisplayName("API back-office /internal/aml/alerts — accessible par COMPLIANCE")
    void backOffice_listAlerts_complianceRole() throws Exception {
        var compliance = factory.adminPrincipal();

        mockMvc.perform(get("/api/internal/aml/alerts")
                .with(SecurityMockMvcRequestPostProcessors.user("compliance@ebithex.io")
                    .roles("COMPLIANCE")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("API back-office /internal/aml/alerts — MERCHANT reçoit 403")
    void backOffice_listAlerts_merchantForbidden() throws Exception {
        mockMvc.perform(get("/api/internal/aml/alerts")
                .header("X-API-Key", merchant.apiKey()))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Review alerte AML via service → statut CLEARED, resolutionNote enregistré")
    void reviewAlert_updatesStatus() {
        AmlAlert alert = AmlAlert.builder()
            .merchantId(merchant.merchantId())
            .ruleCode("VELOCITY_DAILY")
            .severity(com.ebithex.aml.domain.AmlSeverity.HIGH)
            .status(AmlStatus.OPEN)
            .details("Test alert")
            .amount(BigDecimal.valueOf(5000))
            .currency("XOF")
            .build();
        alert = amlAlertRepository.save(alert);

        AmlAlert reviewed = screeningService.review(alert.getId(), AmlStatus.CLEARED,
            "Faux positif - marchand connu", "compliance@ebithex.io");

        assertThat(reviewed.getStatus()).isEqualTo(AmlStatus.CLEARED);
        assertThat(reviewed.getReviewedBy()).isEqualTo("compliance@ebithex.io");
        assertThat(reviewed.getResolutionNote()).contains("Faux positif");
    }
}