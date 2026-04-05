package com.ebithex.reconciliation;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.payment.application.OperatorReconciliationService;
import com.ebithex.payment.domain.DiscrepancyType;
import com.ebithex.payment.domain.OperatorStatement;
import com.ebithex.payment.domain.OperatorStatementLine;
import com.ebithex.payment.domain.OperatorStatementStatus;
import com.ebithex.payment.dto.StatementImportResult;
import com.ebithex.payment.infrastructure.OperatorStatementLineRepository;
import com.ebithex.payment.infrastructure.OperatorStatementRepository;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration — Réconciliation automatisée des relevés opérateurs.
 *
 * Couvre :
 *  - Import CSV + réconciliation automatique
 *  - Détection MATCHED (concordance parfaite)
 *  - Détection MISSING_IN_EBITHEX (référence inconnue de notre système)
 *  - Détection AMOUNT_MISMATCH (écart > 0.01)
 *  - Détection STATUS_MISMATCH (opérateur SUCCESS mais Ebithex FAILED)
 *  - Compteurs totalLines / matchedLines / discrepancyLines corrects
 *  - Statut RECONCILED quand 0 anomalie, DISCREPANCY_FOUND sinon
 *  - Doublon import (même opérateur + date) → STATEMENT_ALREADY_EXISTS
 *  - CSV malformé → INVALID_CSV
 *  - reconcileAllPending() traite tous les PENDING
 */
@DisplayName("OperatorStatement — Import CSV + Réconciliation")
class OperatorStatementReconciliationTest extends AbstractIntegrationTest {

    @Autowired private OperatorReconciliationService reconciliationService;
    @Autowired private OperatorStatementRepository   statementRepository;
    @Autowired private OperatorStatementLineRepository lineRepository;
    @Autowired private TransactionRepository          transactionRepository;
    @Autowired private TestDataFactory                factory;
    @Autowired private MerchantRepository             merchantRepository;

    private static final LocalDate STATEMENT_DATE = LocalDate.of(2026, 3, 17);
    private static final OperatorType OPERATOR = OperatorType.MTN_MOMO_CI;

    private final List<UUID> statementIds = new ArrayList<>();
    private final List<UUID> txIds = new ArrayList<>();
    private UUID testMerchantId;

    @BeforeEach
    void setUp() {
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.success(
                "OP-RECON-MATCH-001", null, "OK"));

        Merchant m = Merchant.builder()
            .businessName("Reconciliation Test Merchant")
            .email(factory.uniqueEmail())
            .hashedSecret("not-used")
            .country("CI").active(true).kycStatus(com.ebithex.merchant.domain.KycStatus.NONE)
            .build();
        merchantRepository.save(m);
        testMerchantId = m.getId();
    }

    @AfterEach
    void tearDown() {
        txIds.forEach(transactionRepository::deleteById);
        txIds.clear();
        statementIds.forEach(id -> {
            lineRepository.deleteByStatementId(id);
            statementRepository.deleteById(id);
        });
        statementIds.clear();
        if (testMerchantId != null) {
            merchantRepository.deleteById(testMerchantId);
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Import CSV → relevé créé, lignes parsées, totalLines correct")
    void import_createsStatementWithLines() throws Exception {
        String csv = csv(
            "OP-001,5000.00,XOF,SUCCESS,2026-03-17T10:00:00",
            "OP-002,3000.00,XOF,FAILED,2026-03-17T10:05:00"
        );

        OperatorStatement stmt = reconciliationService.importStatement(
            OPERATOR, STATEMENT_DATE, new StringReader(csv), null);
        statementIds.add(stmt.getId());

        assertThat(stmt.getTotalLines()).isEqualTo(2);
        assertThat(stmt.getOperator()).isEqualTo(OPERATOR);
        assertThat(stmt.getStatementDate()).isEqualTo(STATEMENT_DATE);
    }

    @Test
    @DisplayName("Import doublon (même opérateur + date) → STATEMENT_ALREADY_EXISTS")
    void import_duplicate_throwsAlreadyExists() throws Exception {
        String csv = csv("OP-DUP-001,5000.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement first = reconciliationService.importStatement(
            OPERATOR, STATEMENT_DATE.minusDays(1), new StringReader(csv), null);
        statementIds.add(first.getId());

        assertThatThrownBy(() ->
            reconciliationService.importStatement(
                OPERATOR, STATEMENT_DATE.minusDays(1), new StringReader(csv), null)
        ).isInstanceOf(EbithexException.class)
         .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode()).isEqualTo(ErrorCode.STATEMENT_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("CSV malformé (< 4 colonnes) → INVALID_CSV")
    void import_malformedCsv_throwsInvalidCsv() {
        String csv = "header\nOP-MALFORMED\n";

        assertThatThrownBy(() ->
            reconciliationService.importStatement(
                OPERATOR, STATEMENT_DATE.minusDays(2), new StringReader(csv), null)
        ).isInstanceOf(EbithexException.class)
         .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CSV));
    }

    // ── Réconciliation — MATCHED ──────────────────────────────────────────────

    @Test
    @DisplayName("Ligne concordante → DiscrepancyType.MATCHED")
    void reconcile_matchedLine_isMatched() throws Exception {
        // Créer une transaction avec une référence opérateur connue
        String operatorRef = "OP-MATCH-" + System.nanoTime();
        createTransactionWithOperatorRef(operatorRef, new BigDecimal("5000.00"), TransactionStatus.SUCCESS);

        // CSV avec la même référence et le même montant
        String csv = csv(operatorRef + ",5000.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        StatementImportResult result = reconciliationService.reconcile(stmt.getId());

        assertThat(result.matchedLines()).isEqualTo(1);
        assertThat(result.discrepancyLines()).isZero();
        assertThat(result.status()).isEqualTo(OperatorStatementStatus.RECONCILED.name());

        List<OperatorStatementLine> lines = lineRepository.findByStatementId(stmt.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.MATCHED);
        assertThat(lines.get(0).getEbithexReference()).isNotBlank();
    }

    // ── Réconciliation — MISSING_IN_EBITHEX ───────────────────────────────────

    @Test
    @DisplayName("Référence opérateur inconnue → MISSING_IN_EBITHEX")
    void reconcile_unknownRef_isMissingInEbithex() throws Exception {
        String csv = csv("UNKNOWN-OP-REF-XYZ,5000.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        StatementImportResult result = reconciliationService.reconcile(stmt.getId());

        assertThat(result.discrepancyLines()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(OperatorStatementStatus.DISCREPANCY_FOUND.name());

        List<OperatorStatementLine> lines = lineRepository.findByStatementId(stmt.getId());
        assertThat(lines.get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.MISSING_IN_EBITHEX);
        assertThat(lines.get(0).getDiscrepancyNote()).isNotBlank();
    }

    // ── Réconciliation — AMOUNT_MISMATCH ──────────────────────────────────────

    @Test
    @DisplayName("Montant différent (écart > 0.01) → AMOUNT_MISMATCH")
    void reconcile_amountMismatch_isAmountMismatch() throws Exception {
        String operatorRef = "OP-AMT-" + System.nanoTime();
        createTransactionWithOperatorRef(operatorRef, new BigDecimal("5000.00"), TransactionStatus.SUCCESS);

        // CSV avec montant différent (5100 != 5000)
        String csv = csv(operatorRef + ",5100.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        StatementImportResult result = reconciliationService.reconcile(stmt.getId());

        assertThat(result.discrepancyLines()).isEqualTo(1);
        List<OperatorStatementLine> lines = lineRepository.findByStatementId(stmt.getId());
        assertThat(lines.get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(lines.get(0).getDiscrepancyNote()).contains("5000");
        assertThat(lines.get(0).getDiscrepancyNote()).contains("5100");
    }

    @Test
    @DisplayName("Écart de montant ≤ 0.01 (arrondi opérateur) → MATCHED")
    void reconcile_tinyAmountDiff_isMatched() throws Exception {
        String operatorRef = "OP-TINY-" + System.nanoTime();
        createTransactionWithOperatorRef(operatorRef, new BigDecimal("5000.00"), TransactionStatus.SUCCESS);

        // Écart de 0.01 → dans la tolérance
        String csv = csv(operatorRef + ",5000.01,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        StatementImportResult result = reconciliationService.reconcile(stmt.getId());
        assertThat(result.matchedLines()).isEqualTo(1);
        assertThat(result.discrepancyLines()).isZero();
    }

    // ── Réconciliation — STATUS_MISMATCH ──────────────────────────────────────

    @Test
    @DisplayName("Opérateur=SUCCESS, Ebithex=FAILED → STATUS_MISMATCH")
    void reconcile_statusMismatch_operatorSuccessEbithexFailed() throws Exception {
        String operatorRef = "OP-STATUS-" + System.nanoTime();
        createTransactionWithOperatorRef(operatorRef, new BigDecimal("5000.00"), TransactionStatus.FAILED);

        String csv = csv(operatorRef + ",5000.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        StatementImportResult result = reconciliationService.reconcile(stmt.getId());

        assertThat(result.discrepancyLines()).isEqualTo(1);
        List<OperatorStatementLine> lines = lineRepository.findByStatementId(stmt.getId());
        assertThat(lines.get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("Opérateur=COMPLETED (alias SUCCESS), Ebithex=SUCCESS → MATCHED")
    void reconcile_completedIsEquivalentToSuccess() throws Exception {
        String operatorRef = "OP-COMPLETED-" + System.nanoTime();
        createTransactionWithOperatorRef(operatorRef, new BigDecimal("5000.00"), TransactionStatus.SUCCESS);

        String csv = csv(operatorRef + ",5000.00,XOF,COMPLETED,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        StatementImportResult result = reconciliationService.reconcile(stmt.getId());
        assertThat(result.matchedLines()).isEqualTo(1);
        assertThat(result.discrepancyLines()).isZero();
    }

    @Test
    @DisplayName("Ebithex=REFUNDED, opérateur=SUCCESS → MATCHED (REFUNDED est SUCCESS côté Ebithex)")
    void reconcile_refundedEquivalentToSuccess() throws Exception {
        String operatorRef = "OP-REFUNDED-" + System.nanoTime();
        createTransactionWithOperatorRef(operatorRef, new BigDecimal("5000.00"), TransactionStatus.REFUNDED);

        String csv = csv(operatorRef + ",5000.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        StatementImportResult result = reconciliationService.reconcile(stmt.getId());
        assertThat(result.matchedLines()).isEqualTo(1);
    }

    // ── reconcileAllPending ───────────────────────────────────────────────────

    @Test
    @DisplayName("reconcileAllPending() → traite tous les relevés PENDING")
    void reconcileAllPending_processesAllPendingStatements() throws Exception {
        String opRef1 = "OP-PEND-A-" + System.nanoTime();
        String opRef2 = "OP-PEND-B-" + System.nanoTime();
        createTransactionWithOperatorRef(opRef1, new BigDecimal("1000.00"), TransactionStatus.SUCCESS);
        createTransactionWithOperatorRef(opRef2, new BigDecimal("2000.00"), TransactionStatus.SUCCESS);

        OperatorStatement s1 = importOnly(csv(opRef1 + ",1000.00,XOF,SUCCESS,"), LocalDate.of(2026, 1, 1));
        OperatorStatement s2 = importOnly(csv(opRef2 + ",2000.00,XOF,SUCCESS,"), LocalDate.of(2026, 1, 2));

        reconciliationService.reconcileAllPending();

        OperatorStatement r1 = statementRepository.findById(s1.getId()).orElseThrow();
        OperatorStatement r2 = statementRepository.findById(s2.getId()).orElseThrow();
        assertThat(r1.getStatus()).isEqualTo(OperatorStatementStatus.RECONCILED);
        assertThat(r2.getStatus()).isEqualTo(OperatorStatementStatus.RECONCILED);
    }

    // ── API REST (via MockMvc) ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /internal/reconciliation/statements → retourne la liste paginée")
    void api_listStatements_returnsPaginatedList() throws Exception {
        String csv = csv("OP-API-001,5000.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/internal/reconciliation/statements")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(factory.adminPrincipal()))
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
         .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
             .jsonPath("$.data.content").isArray());
    }

    @Test
    @DisplayName("GET /internal/reconciliation/statements/{id}/discrepancies → retourne les anomalies")
    void api_getDiscrepancies_returnsDiscrepancyLines() throws Exception {
        // Import d'un relevé avec une référence inconnue → MISSING_IN_EBITHEX
        String csv = csv("UNKNOWN-REF-DISC-001,5000.00,XOF,SUCCESS,2026-03-17T10:00:00");
        OperatorStatement stmt = doImportAndReconcile(csv);
        reconciliationService.reconcile(stmt.getId()); // re-run pour s'assurer

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/internal/reconciliation/statements/" + stmt.getId() + "/discrepancies")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(factory.adminPrincipal()))
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
         .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
             .jsonPath("$.data.content[0].discrepancyType").value("MISSING_IN_EBITHEX"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String csv(String... lines) {
        return "operator_reference,amount,currency,status,transaction_date\n"
            + String.join("\n", lines);
    }

    /**
     * Importe un CSV et lance immédiatement la réconciliation.
     * Utilise une date unique pour éviter les collisions STATEMENT_ALREADY_EXISTS.
     */
    private OperatorStatement doImportAndReconcile(String csv) throws Exception {
        LocalDate uniqueDate = STATEMENT_DATE.minusDays(statementIds.size() + 10L);
        OperatorStatement stmt = reconciliationService.importStatement(
            OPERATOR, uniqueDate, new StringReader(csv), null);
        statementIds.add(stmt.getId());
        return stmt;
    }

    /**
     * Importe un CSV sans lancer la réconciliation (statut PENDING).
     */
    private OperatorStatement importOnly(String csv, LocalDate date) throws Exception {
        OperatorStatement stmt = reconciliationService.importStatement(
            OPERATOR, date, new StringReader(csv), null);
        statementIds.add(stmt.getId());
        // Reset to PENDING (importStatement lance reconcile inline depuis le controller)
        // Ici on appelle directement le service, qui n'auto-réconcile pas
        return stmt;
    }

    /**
     * Crée une transaction directement en base avec une référence opérateur et un statut donnés.
     */
    private void createTransactionWithOperatorRef(String operatorRef, BigDecimal amount,
                                                   TransactionStatus status) {
        com.ebithex.payment.domain.Transaction tx = com.ebithex.payment.domain.Transaction.builder()
            .ebithexReference("AP-RECON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
            .merchantReference("MR-" + System.nanoTime())
            .merchantId(testMerchantId)
            .amount(amount)
            .feeAmount(amount.multiply(new BigDecimal("0.01")))
            .netAmount(amount.multiply(new BigDecimal("0.99")))
            .currency(com.ebithex.shared.domain.Currency.XOF)
            .phoneNumber("encrypted_placeholder")
            .operator(OperatorType.MTN_MOMO_CI)
            .operatorReference(operatorRef)
            .status(status)
            .build();
        transactionRepository.save(tx);
        txIds.add(tx.getId());
    }
}