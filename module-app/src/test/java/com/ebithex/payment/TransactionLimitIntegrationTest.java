package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.domain.KycStatus;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration — Plafonds journalier et mensuel (TransactionLimitService).
 *
 * Couvre :
 *  - Paiement sous plafond → accepté
 *  - Paiement dépassant le plafond journalier → 400 DAILY_LIMIT_EXCEEDED
 *  - Paiement dépassant le plafond mensuel → 400 MONTHLY_LIMIT_EXCEEDED
 *  - Aucun plafond configuré → toujours accepté
 *  - Mode sandbox → plafond ignoré
 */
@DisplayName("Transaction Limits — Plafonds journalier et mensuel")
class TransactionLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory    factory;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ApiKeyService      apiKeyService;
    @Autowired private PasswordEncoder    passwordEncoder;

    @BeforeEach
    void setUp() {
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-" + System.nanoTime(), null, "En attente"));
    }

    @Test
    @DisplayName("Paiement sous plafond journalier → 200")
    void payment_underDailyLimit_accepted() {
        MerchantWithKey m = createMerchant(new BigDecimal("10000"), null, false);

        assertThat(pay(m, "5000").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Cumul dépasse plafond journalier → 400 DAILY_LIMIT_EXCEEDED")
    void payment_exceedsDailyLimit_returns400() {
        MerchantWithKey m = createMerchant(new BigDecimal("3000"), null, false);

        assertThat(pay(m, "2000").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> resp = pay(m, "2000");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("DAILY_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("Cumul dépasse plafond mensuel → 400 MONTHLY_LIMIT_EXCEEDED")
    void payment_exceedsMonthlyLimit_returns400() {
        MerchantWithKey m = createMerchant(null, new BigDecimal("3000"), false);

        assertThat(pay(m, "2000").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> resp = pay(m, "2000");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("MONTHLY_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("Aucun plafond configuré → tous les paiements acceptés")
    void payment_noLimit_alwaysAccepted() {
        MerchantWithKey m = createMerchant(null, null, false);

        assertThat(pay(m, "500000").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pay(m, "500000").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Mode sandbox — plafond journalier ignoré")
    void payment_sandboxMode_limitsNotChecked() {
        MerchantWithKey m = createMerchant(new BigDecimal("100"), null, true);

        // Paiement de 5000 alors que le plafond est 100 → accepté car sandbox
        ResponseEntity<Map> resp = pay(m, "5000");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record MerchantWithKey(UUID merchantId, String rawApiKey) {}

    private MerchantWithKey createMerchant(BigDecimal dailyLimit, BigDecimal monthlyLimit, boolean testMode) {
        Merchant m = Merchant.builder()
            .businessName("LimitTest-" + System.nanoTime())
            .email(factory.uniqueEmail())
            .hashedSecret(passwordEncoder.encode("Test@1234!"))
            .country("CI").active(true).kycVerified(true).kycStatus(KycStatus.APPROVED)
            .dailyPaymentLimit(dailyLimit)
            .monthlyPaymentLimit(monthlyLimit)
            .testMode(testMode)
            .build();
        Merchant saved = merchantRepository.save(m);
        String[] keys = apiKeyService.createInitialKeys(saved.getId());
        return new MerchantWithKey(saved.getId(), keys[0]);
    }

    private ResponseEntity<Map> pay(MerchantWithKey m, String amount) {
        Map<String, Object> body = Map.of(
            "amount",            new java.math.BigDecimal(amount),
            "phoneNumber",       "+2250501" + String.format("%05d", System.nanoTime() % 100000),
            "merchantReference", "REF-" + UUID.randomUUID()
        );
        return restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(m.rawApiKey())),
            Map.class);
    }
}