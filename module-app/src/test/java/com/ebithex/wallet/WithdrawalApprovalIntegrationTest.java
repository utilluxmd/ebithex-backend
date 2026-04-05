package com.ebithex.wallet;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.shared.security.Role;
import com.ebithex.wallet.application.WalletService;
import com.ebithex.wallet.domain.MerchantWithdrawal;
import com.ebithex.wallet.domain.WithdrawalStatus;
import com.ebithex.wallet.infrastructure.MerchantWithdrawalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Workflow d'approbation des retraits.
 *
 * Couvre :
 *  - Soumission retrait → statut PENDING, wallet NON débité
 *  - Finance approuve → statut APPROVED, wallet débité
 *  - Finance rejette → statut REJECTED, wallet intact
 *  - Approbation d'un retrait déjà traité → 400
 *  - Confirmation exécution (APPROVED → EXECUTED)
 *  - Historique retraits marchand
 *  - Accès finance uniquement pour approve/reject
 */
@DisplayName("Withdrawal Approval — Workflow PENDING → APPROVED/REJECTED → EXECUTED")
class WithdrawalApprovalIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory             factory;
    @Autowired private WalletService               walletService;
    @Autowired private MerchantWithdrawalRepository withdrawalRepository;

    private TestDataFactory.MerchantCredentials merchant;
    private EbithexPrincipal                    financeUser;

    @BeforeEach
    void setUp() {
        merchant = factory.registerKycVerifiedMerchant();
        // Créer un solde initial en simulant un crédit direct
        injectWalletBalance(merchant.merchantId(), "50000");
        // Créer un opérateur finance réel en DB (reviewed_by FK dans withdrawal_requests)
        TestDataFactory.StaffUserCredentials financeOp = factory.createStaffUser(Role.FINANCE);
        financeUser = EbithexPrincipal.builder()
            .id(financeOp.staffUserId())
            .email(financeOp.email())
            .roles(Set.of(Role.FINANCE))
            .active(true)
            .build();
    }

    @Test
    @DisplayName("Soumission retrait → PENDING, wallet NON débité immédiatement")
    void submitWithdrawal_returnsPending_walletNotDebited() {
        BigDecimal balanceBefore = getBalance();

        ResponseEntity<Map> resp = submitWithdrawal("10000", "Retrait test");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PENDING");
        assertThat(data.get("id")).isNotNull();

        // Wallet NOT débité
        assertThat(getBalance()).isEqualByComparingTo(balanceBefore);
    }

    @Test
    @DisplayName("Finance approuve → APPROVED, wallet débité")
    void approveWithdrawal_walletDebited() throws Exception {
        BigDecimal balanceBefore = getBalance();
        String withdrawalId = submitAndGetId("10000");

        mockMvc.perform(
            put("/api/internal/finance/withdrawals/" + withdrawalId + "/approve")
                .with(user(financeUser))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // Wallet débité de 10 000
        BigDecimal balanceAfter = getBalance();
        assertThat(balanceBefore.subtract(balanceAfter))
            .isEqualByComparingTo(new java.math.BigDecimal("10000"));
    }

    @Test
    @DisplayName("Finance rejette → REJECTED, wallet intact")
    void rejectWithdrawal_walletUntouched() throws Exception {
        BigDecimal balanceBefore = getBalance();
        String withdrawalId = submitAndGetId("10000");

        mockMvc.perform(
            put("/api/internal/finance/withdrawals/" + withdrawalId + "/reject")
                .with(user(financeUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Documents insuffisants\"}")
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.status").value("REJECTED"))
         .andExpect(jsonPath("$.data.rejectionReason").value("Documents insuffisants"));

        assertThat(getBalance()).isEqualByComparingTo(balanceBefore);
    }

    @Test
    @DisplayName("Double approbation → 400 WITHDRAWAL_ALREADY_PROCESSED")
    void approveWithdrawal_twice_returns400() throws Exception {
        String withdrawalId = submitAndGetId("10000");

        mockMvc.perform(
            put("/api/internal/finance/withdrawals/" + withdrawalId + "/approve")
                .with(user(financeUser))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk());

        mockMvc.perform(
            put("/api/internal/finance/withdrawals/" + withdrawalId + "/approve")
                .with(user(financeUser))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isBadRequest())
         .andExpect(jsonPath("$.errorCode").value("WITHDRAWAL_ALREADY_PROCESSED"));
    }

    @Test
    @DisplayName("APPROVED → EXECUTED via confirmation exécution")
    void executeWithdrawal_approvedToExecuted() throws Exception {
        String withdrawalId = submitAndGetId("10000");

        mockMvc.perform(
            put("/api/internal/finance/withdrawals/" + withdrawalId + "/approve")
                .with(user(financeUser))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk());

        mockMvc.perform(
            put("/api/internal/finance/withdrawals/" + withdrawalId + "/execute")
                .with(user(financeUser))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.status").value("EXECUTED"));
    }

    @Test
    @DisplayName("Solde insuffisant lors de l'approbation → 400 INSUFFICIENT_BALANCE")
    void approveWithdrawal_insufficientBalance_returns400() throws Exception {
        // Insérer directement une demande de retrait supérieure au solde (bypass du contrôle à la soumission)
        MerchantWithdrawal oversized = withdrawalRepository.save(
            MerchantWithdrawal.builder()
                .merchantId(merchant.merchantId())
                .amount(new BigDecimal("9999999"))
                .currency(Currency.XOF)
                .reference("WD-INSUF-" + UUID.randomUUID())
                .description("Test solde insuffisant")
                .status(WithdrawalStatus.PENDING)
                .build()
        );

        mockMvc.perform(
            put("/api/internal/finance/withdrawals/" + oversized.getId() + "/approve")
                .with(user(financeUser))
                .contentType(MediaType.APPLICATION_JSON)
        ).andExpect(status().isBadRequest())
         .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("Historique retraits marchand — paginated")
    void listWithdrawals_merchant_seesOwnHistory() {
        submitWithdrawal("5000", "W1");
        submitWithdrawal("3000", "W2");

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/wallet/withdrawals"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> page = (Map<?, ?>) resp.getBody().get("data");
        assertThat((Integer) page.get("totalElements")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Finance voit tous les retraits PENDING")
    void listWithdrawals_finance_seesPendingRequests() throws Exception {
        submitAndGetId("5000");

        mockMvc.perform(
            get("/api/internal/finance/withdrawals")
                .with(user(financeUser))
                .param("status", "PENDING")
        ).andExpect(status().isOk())
         .andExpect(jsonPath("$.data.content").isArray());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map> submitWithdrawal(String amount, String description) {
        Map<String, Object> body = Map.of("amount", amount, "description", description);
        return restTemplate.exchange(
            url("/v1/wallet/withdrawals"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchant.apiKey())), Map.class);
    }

    private String submitAndGetId(String amount) {
        ResponseEntity<Map> resp = submitWithdrawal(amount, "Test");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) ((Map<?, ?>) resp.getBody().get("data")).get("id");
    }

    private BigDecimal getBalance() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/wallet/balance"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchant.apiKey())), Map.class);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        return new BigDecimal(data.get("availableBalance").toString());
    }

    private void injectWalletBalance(UUID merchantId, String amount) {
        walletService.creditPayment(merchantId, new java.math.BigDecimal(amount),
            "AP-INJECT-" + UUID.randomUUID(), com.ebithex.shared.domain.Currency.XOF);
    }
}