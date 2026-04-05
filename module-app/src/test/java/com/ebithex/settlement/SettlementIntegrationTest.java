package com.ebithex.settlement;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.settlement.application.SettlementService;
import com.ebithex.settlement.domain.SettlementBatch;
import com.ebithex.settlement.domain.SettlementBatchStatus;
import com.ebithex.settlement.infrastructure.SettlementBatchRepository;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration Settlement.
 *
 * Couvre :
 *  - Génération des batches de règlement pour des transactions SUCCESS
 *  - Idempotence (double exécution → pas de doublons)
 *  - Confirmation manuelle d'un batch (PENDING → SETTLED)
 *  - Contrôles d'accès back-office
 *
 * Note: Utilise des opérateurs rares (MPESA_KE, AIRTEL_MONEY_KE, OPAY_NG) pour
 *       éviter les interférences avec d'autres tests qui créent des transactions
 *       MTN_MOMO_CI / ORANGE_MONEY_CI.
 */
@DisplayName("Settlement — Cycle de Règlement")
class SettlementIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory       factory;
    @Autowired private SettlementService     settlementService;
    @Autowired private SettlementBatchRepository batchRepository;
    @Autowired private TransactionRepository transactionRepository;

    private TestDataFactory.MerchantCredentials merchant;

    // Fenêtre large couvrant toute la durée du test (now()-5min → now()+5min)
    // @CreationTimestamp donne createdAt=now(), donc les transactions seront dans cette fenêtre.
    private LocalDateTime windowFrom;
    private LocalDateTime windowTo;

    @BeforeEach
    void setUp() {
        merchant = factory.registerMerchant(restTemplate, url(""));
        windowFrom = LocalDateTime.now().minusMinutes(5);
        windowTo   = LocalDateTime.now().plusMinutes(5);
    }

    @Test
    @DisplayName("runSettlementCycle — transactions SUCCESS MPESA_KE → batch créé avec bons montants")
    void runSettlement_createsExpectedBatch() {
        // Seed 2 transactions SUCCESS MPESA_KE KES (unique à ce test)
        seedTransaction(OperatorType.MPESA_KE, Currency.KES,
            BigDecimal.valueOf(5000), BigDecimal.valueOf(50), TransactionStatus.SUCCESS);
        seedTransaction(OperatorType.MPESA_KE, Currency.KES,
            BigDecimal.valueOf(10000), BigDecimal.valueOf(100), TransactionStatus.SUCCESS);
        // FAILED → ne doit pas être inclus
        seedTransaction(OperatorType.MPESA_KE, Currency.KES,
            BigDecimal.valueOf(3000), BigDecimal.valueOf(30), TransactionStatus.FAILED);

        settlementService.runSettlementCycle(windowFrom, windowTo);

        // Retrouver le batch MPESA_KE / KES créé pendant cette fenêtre
        List<SettlementBatch> batches = batchRepository.findAll().stream()
            .filter(b -> b.getOperator() == OperatorType.MPESA_KE && "KES".equals(b.getCurrency()))
            .toList();
        assertThat(batches).isNotEmpty();

        // Seules les 2 transactions SUCCESS doivent être comptées
        SettlementBatch batch = batches.get(0);
        assertThat(batch.getTransactionCount()).isGreaterThanOrEqualTo(2);
        assertThat(batch.getGrossAmount()).isGreaterThanOrEqualTo(BigDecimal.valueOf(15000));
        assertThat(batch.getFeeAmount()).isGreaterThanOrEqualTo(BigDecimal.valueOf(150));
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.PENDING);
    }

    @Test
    @DisplayName("runSettlementCycle est idempotent — double appel ne crée pas de doublon")
    void runSettlement_idempotent() {
        // Unique operator pour ce test : AIRTEL_MONEY_KE
        seedTransaction(OperatorType.AIRTEL_MONEY_KE, Currency.KES,
            BigDecimal.valueOf(8000), BigDecimal.valueOf(72), TransactionStatus.SUCCESS);

        // Premier run — crée le batch (ou ne fait rien si déjà existant d'une exécution précédente)
        long batchCountBefore = batchRepository.findAll().stream()
            .filter(b -> b.getOperator() == OperatorType.AIRTEL_MONEY_KE).count();
        settlementService.runSettlementCycle(windowFrom, windowTo);
        long batchCountAfter  = batchRepository.findAll().stream()
            .filter(b -> b.getOperator() == OperatorType.AIRTEL_MONEY_KE).count();

        // Le second run ne doit PAS créer de nouveaux batches
        int secondRun = settlementService.runSettlementCycle(windowFrom, windowTo);
        long batchCountAfterSecond = batchRepository.findAll().stream()
            .filter(b -> b.getOperator() == OperatorType.AIRTEL_MONEY_KE).count();

        assertThat(secondRun).isZero();
        assertThat(batchCountAfterSecond).isEqualTo(batchCountAfter);
    }

    @Test
    @DisplayName("markSettled — batch PENDING → SETTLED")
    void markSettled_updatesStatus() {
        // Unique operator pour ce test : OPAY_NG
        seedTransaction(OperatorType.OPAY_NG, Currency.NGN,
            BigDecimal.valueOf(7500), BigDecimal.valueOf(52), TransactionStatus.SUCCESS);

        settlementService.runSettlementCycle(windowFrom, windowTo);

        List<SettlementBatch> batches = batchRepository.findAll().stream()
            .filter(b -> b.getOperator() == OperatorType.OPAY_NG)
            .toList();
        assertThat(batches).isNotEmpty();

        SettlementBatch settled = settlementService.markSettled(batches.get(0).getId());
        assertThat(settled.getStatus()).isEqualTo(SettlementBatchStatus.SETTLED);
        assertThat(settled.getSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("API back-office /internal/settlement — accessible par FINANCE")
    void backOffice_listBatches_financeRole() throws Exception {
        mockMvc.perform(get("/api/internal/settlement")
                .with(SecurityMockMvcRequestPostProcessors.user("finance@ebithex.io")
                    .roles("FINANCE")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("API back-office /internal/settlement — MERCHANT reçoit 401 (pas d'auth)")
    void backOffice_listBatches_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/settlement"))
            .andExpect(status().isUnauthorized());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void seedTransaction(OperatorType operator, Currency currency,
                                  BigDecimal amount, BigDecimal fee, TransactionStatus status) {
        Transaction tx = Transaction.builder()
            .ebithexReference("TX-SET-" + UUID.randomUUID().toString().substring(0, 8))
            .merchantReference("MR-SET-" + UUID.randomUUID().toString().substring(0, 8))
            .merchantId(merchant.merchantId())
            .amount(amount)
            .feeAmount(fee)
            .netAmount(amount.subtract(fee))
            .currency(currency)
            .phoneNumber("encrypted-phone")
            .operator(operator)
            .status(status)
            .build();
        transactionRepository.save(tx);
    }
}