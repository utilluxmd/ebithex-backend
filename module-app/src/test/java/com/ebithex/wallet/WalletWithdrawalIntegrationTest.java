package com.ebithex.wallet;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.shared.security.Role;
import com.ebithex.wallet.application.WalletService;
import com.ebithex.wallet.dto.WithdrawalResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration — Soumission de retrait wallet marchand.
 *
 * NOTE : depuis l'implémentation du workflow d'approbation, les retraits sont
 * créés en statut PENDING et ne débitent PAS immédiatement le wallet.
 * Le débit intervient lors de l'approbation finance (WithdrawalApprovalIntegrationTest).
 *
 * Couvre :
 *  - Marchand non-KYC → 403
 *  - Retrait sans auth → 401
 *  - Montant en-dessous du minimum → 400 (validation)
 *  - Retrait valide → 200 PENDING, wallet NON débité
 *  - Retrait valide → enregistré dans le grand livre des retraits
 *  - Idempotence — même référence → erreur (contrainte UNIQUE)
 */
@DisplayName("Wallet — Soumission retrait marchand (workflow PENDING)")
class WalletWithdrawalIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory factory;
    @Autowired private WalletService   walletService;

    private TestDataFactory.MerchantCredentials kycMerchant;

    @BeforeEach
    void setUp() {
        kycMerchant = factory.registerKycVerifiedMerchant();
        walletService.creditPayment(kycMerchant.merchantId(), new BigDecimal("50000"),
            "AP-WD-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12),
            Currency.XOF);
    }

    // ── Accès ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sans authentification → 401")
    void withdrawal_noAuth_returns401() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/v1/wallet/withdrawals"),
            Map.of("amount", 5000),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Marchand non-KYC → 403")
    void withdrawal_nonKycMerchant_returns403() throws Exception {
        UUID merchantId = UUID.randomUUID();
        EbithexPrincipal nonKycPrincipal = EbithexPrincipal.builder()
            .id(merchantId)
            .email("nonkyc@test.ebithex.io")
            .roles(Set.of(Role.MERCHANT))
            .active(true)
            .merchantId(merchantId)
            .build();

        mockMvc.perform(post("/api/v1/wallet/withdrawals")
                .with(user(nonKycPrincipal))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 5000}"))
            .andExpect(status().isForbidden());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Montant inférieur au minimum (100) → 400")
    void withdrawal_belowMinimum_returns400() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/wallet/withdrawals"), HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 50),
                factory.apiKeyHeaders(kycMerchant.apiKey())),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Workflow PENDING ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Retrait valide → 200 PENDING, wallet NON débité immédiatement")
    void withdrawal_valid_pendingStatus_walletNotDebited() {
        BigDecimal balanceBefore = walletService.getBalance(kycMerchant.merchantId(), Currency.XOF).availableBalance();

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/wallet/withdrawals"), HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 10000, "description", "Retrait test"),
                factory.apiKeyHeaders(kycMerchant.apiKey())),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PENDING");
        assertThat(data.get("id")).isNotNull();

        // Wallet NON débité à ce stade
        BigDecimal balanceAfter = walletService.getBalance(kycMerchant.merchantId(), Currency.XOF).availableBalance();
        assertThat(balanceAfter).isEqualByComparingTo(balanceBefore);
    }

    @Test
    @DisplayName("Retrait valide → retrait visible dans l'historique marchand")
    void withdrawal_valid_visibleInHistory() {
        restTemplate.exchange(
            url("/v1/wallet/withdrawals"), HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 5000, "description", "Test historique"),
                factory.apiKeyHeaders(kycMerchant.apiKey())),
            Map.class);

        ResponseEntity<Map> listResp = restTemplate.exchange(
            url("/v1/wallet/withdrawals"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(kycMerchant.apiKey())),
            Map.class);

        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> page = (Map<?, ?>) listResp.getBody().get("data");
        assertThat(((Number) page.get("totalElements")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Idempotence — même référence retourne la demande existante sans doublon")
    void withdrawal_sameReference_idempotent() {
        String ref = "WD-IDEM-" + System.nanoTime();
        WithdrawalResponse first  = walletService.requestWithdrawal(kycMerchant.merchantId(), new BigDecimal("5000"), ref, null, Currency.XOF);
        WithdrawalResponse second = walletService.requestWithdrawal(kycMerchant.merchantId(), new BigDecimal("5000"), ref, null, Currency.XOF);

        assertThat(second.id()).isEqualTo(first.id());

        BigDecimal balance = walletService.getBalance(kycMerchant.merchantId(), Currency.XOF).availableBalance();
        assertThat(balance).isEqualByComparingTo(new BigDecimal("50000.00"));
    }
}