package com.ebithex.payment.api;

import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.payment.application.OperatorGateway;
import com.ebithex.payment.application.OperatorReconciliationService;
import com.ebithex.payment.domain.*;
import com.ebithex.payment.dto.StatementImportResult;
import com.ebithex.payment.dto.TransactionStatusResponse;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.dto.PayoutSummaryResponse;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.crypto.EncryptionService;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.shared.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints de réconciliation back-office.
 *
 * Accès (défini dans SecurityConfig) :
 *   GET /internal/reconciliation/**           → RECONCILIATION, FINANCE, COUNTRY_ADMIN, ADMIN, SUPER_ADMIN
 *   GET /internal/reconciliation/export/**    → RECONCILIATION, ADMIN, SUPER_ADMIN
 *
 * COUNTRY_ADMIN ne voit que les transactions/payouts de son pays (filtrage via sous-requête Merchant.country).
 */
@RestController
@RequestMapping("/internal/reconciliation")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Back-office — Réconciliation")
public class ReconciliationController {

    private final TransactionRepository          transactionRepository;
    private final PayoutRepository               payoutRepository;
    private final EncryptionService              encryptionService;
    private final OperatorReconciliationService  reconciliationService;
    private final OperatorGateway                operatorGateway;

    @GetMapping("/transactions")
    @Operation(summary = "Lister les transactions (filtres : dates, statut, opérateur, marchand)")
    public ResponseEntity<ApiResponse<Page<TransactionStatusResponse>>> listTransactions(
            @AuthenticationPrincipal Object rawPrincipal,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) OperatorType operator,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Pageable pageable) {

        EbithexPrincipal principal = rawPrincipal instanceof EbithexPrincipal ap ? ap : null;
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now().plusSeconds(1);
        String country = principal != null && principal.hasCountryScope() ? principal.country() : null;

        Page<TransactionStatusResponse> page = transactionRepository
            .findForReconciliation(country, merchantId, status, operator, effectiveFrom, effectiveTo, pageable)
            .map(t -> toStatusResponse(t, principal));

        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/payouts")
    @Operation(summary = "Lister les décaissements (filtres : dates, statut, marchand)")
    public ResponseEntity<ApiResponse<Page<PayoutSummaryResponse>>> listPayouts(
            @AuthenticationPrincipal Object rawPrincipal,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Pageable pageable) {

        EbithexPrincipal principal = rawPrincipal instanceof EbithexPrincipal ap ? ap : null;
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now().plusSeconds(1);
        String country = principal != null && principal.hasCountryScope() ? principal.country() : null;

        Page<PayoutSummaryResponse> page = payoutRepository
            .findForReconciliation(country, merchantId, status, effectiveFrom, effectiveTo, pageable)
            .map(PayoutSummaryResponse::from);

        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/summary")
    @Operation(summary = "Résumé agrégé par statut (transactions + payouts) sur une période")
    public ResponseEntity<ApiResponse<ReconciliationSummary>> getSummary(
            @AuthenticationPrincipal Object rawPrincipal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        EbithexPrincipal principal = rawPrincipal instanceof EbithexPrincipal ap ? ap : null;
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now().plusSeconds(1);
        String country = principal != null && principal.hasCountryScope() ? principal.country() : null;

        List<StatusAggregate> txAggregates  = toAggregates(transactionRepository.aggregateByStatus(country, effectiveFrom, effectiveTo));
        List<StatusAggregate> poAggregates  = toAggregates(payoutRepository.aggregateByStatus(country, effectiveFrom, effectiveTo));

        return ResponseEntity.ok(ApiResponse.ok(new ReconciliationSummary(txAggregates, poAggregates)));
    }

    @GetMapping("/export/transactions")
    @Operation(summary = "Exporter les transactions en CSV")
    public ResponseEntity<StreamingResponseBody> exportTransactions(
            @AuthenticationPrincipal Object rawPrincipal,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) OperatorType operator,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        EbithexPrincipal principal = rawPrincipal instanceof EbithexPrincipal ap ? ap : null;
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now().plusSeconds(1);
        String country = principal != null && principal.hasCountryScope() ? principal.country() : null;

        StreamingResponseBody body = outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                writer.println("transactionId,ebithexReference,merchantReference,merchantId,status,amount,feeAmount,netAmount,currency,operator,createdAt");
                int pageNum = 0;
                Page<Transaction> page;
                do {
                    page = transactionRepository.findForReconciliation(
                        country, merchantId, status, operator,
                        effectiveFrom, effectiveTo,
                        PageRequest.of(pageNum++, 500));
                    for (Transaction t : page.getContent()) {
                        writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            t.getId(), t.getEbithexReference(), t.getMerchantReference(),
                            t.getMerchantId(), t.getStatus(),
                            t.getAmount(), t.getFeeAmount(), t.getNetAmount(),
                            t.getCurrency(), t.getOperator(), t.getCreatedAt());
                    }
                    writer.flush();
                } while (page.hasNext());
            }
        };

        String filename = "transactions_" + effectiveFrom.toLocalDate() + "_" + effectiveTo.toLocalDate() + ".csv";
        return ResponseEntity.ok()
            .header("Content-Type", "text/csv; charset=UTF-8")
            .header("Content-Disposition", "attachment; filename=" + filename)
            .body(body);
    }

    @GetMapping("/export/payouts")
    @Operation(summary = "Exporter les décaissements en CSV")
    public ResponseEntity<StreamingResponseBody> exportPayouts(
            @AuthenticationPrincipal Object rawPrincipal,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        EbithexPrincipal principal = rawPrincipal instanceof EbithexPrincipal ap ? ap : null;
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now().plusSeconds(1);
        String country = principal != null && principal.hasCountryScope() ? principal.country() : null;

        StreamingResponseBody body = outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                writer.println("payoutId,ebithexReference,merchantReference,merchantId,status,amount,feeAmount,netAmount,currency,operator,beneficiaryName,createdAt");
                int pageNum = 0;
                Page<Payout> page;
                do {
                    page = payoutRepository.findForReconciliation(
                        country, merchantId, status,
                        effectiveFrom, effectiveTo,
                        PageRequest.of(pageNum++, 500));
                    for (Payout p : page.getContent()) {
                        writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            p.getId(), p.getEbithexReference(), p.getMerchantReference(),
                            p.getMerchantId(), p.getStatus(),
                            p.getAmount(), p.getFeeAmount(), p.getNetAmount(),
                            p.getCurrency(), p.getOperator(),
                            p.getBeneficiaryName() != null ? p.getBeneficiaryName() : "",
                            p.getCreatedAt());
                    }
                    writer.flush();
                } while (page.hasNext());
            }
        };

        String filename = "payouts_" + effectiveFrom.toLocalDate() + "_" + effectiveTo.toLocalDate() + ".csv";
        return ResponseEntity.ok()
            .header("Content-Type", "text/csv; charset=UTF-8")
            .header("Content-Disposition", "attachment; filename=" + filename)
            .body(body);
    }

    // ── Relevés opérateurs ────────────────────────────────────────────────────

    @PostMapping(value = "/statements/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importer un relevé opérateur (CSV)",
               description = "Format CSV attendu : operator_reference,amount,currency,status,transaction_date")
    public ResponseEntity<ApiResponse<StatementImportResult>> importStatement(
            @AuthenticationPrincipal Object rawPrincipal,
            @RequestParam OperatorType operator,
            @RequestParam String statementDate,
            @RequestPart("file") MultipartFile file) throws IOException {

        EbithexPrincipal principal = rawPrincipal instanceof EbithexPrincipal ap ? ap : null;
        UUID importedBy = principal != null ? principal.id() : null;
        LocalDate date = LocalDate.parse(statementDate);

        try (Reader reader = new InputStreamReader(file.getInputStream())) {
            OperatorStatement statement = reconciliationService.importStatement(operator, date, reader, importedBy);
            StatementImportResult result = reconciliationService.reconcile(statement.getId());
            return ResponseEntity.ok(ApiResponse.ok("Relevé importé et réconcilié", result));
        }
    }

    @GetMapping("/statements")
    @Operation(summary = "Lister les relevés opérateurs")
    public ResponseEntity<ApiResponse<Page<OperatorStatement>>> listStatements(
            @RequestParam(required = false) OperatorType operator,
            Pageable pageable) {

        Page<OperatorStatement> page = reconciliationService.listStatements(operator, pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/statements/{id}")
    @Operation(summary = "Détail d'un relevé opérateur")
    public ResponseEntity<ApiResponse<OperatorStatement>> getStatement(
            @PathVariable UUID id) {

        return ResponseEntity.ok(ApiResponse.ok(reconciliationService.getStatement(id)));
    }

    @GetMapping("/statements/{id}/discrepancies")
    @Operation(summary = "Anomalies détectées dans un relevé opérateur")
    public ResponseEntity<ApiResponse<Page<OperatorStatementLine>>> getDiscrepancies(
            @PathVariable UUID id,
            Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.ok(reconciliationService.getDiscrepancies(id, pageable)));
    }

    @PostMapping("/statements/{id}/reconcile")
    @Operation(summary = "Relancer la réconciliation d'un relevé (manuel)")
    public ResponseEntity<ApiResponse<StatementImportResult>> rereconcile(
            @PathVariable UUID id) {

        StatementImportResult result = reconciliationService.reconcile(id);
        return ResponseEntity.ok(ApiResponse.ok("Réconciliation terminée", result));
    }

    // ── Solde float opérateur ─────────────────────────────────────────────────

    @GetMapping("/operators/{operator}/balance")
    @Operation(summary = "Vérifier le solde float Ebithex chez un opérateur",
               description = "Interroge l'API opérateur pour obtenir le solde du compte float Ebithex. " +
                             "Permet de s'assurer que le float est suffisant avant les batches de payout. " +
                             "Retourne available=false si l'opérateur ne supporte pas cette opération.")
    public ResponseEntity<ApiResponse<MobileMoneyOperator.BalanceResult>> checkOperatorBalance(
            @PathVariable OperatorType operator) {

        MobileMoneyOperator.BalanceResult result = operatorGateway.checkBalance(operator);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── DTOs internes ────────────────────────────────────────────────────────

    public record StatusAggregate(
        TransactionStatus status,
        long              count,
        BigDecimal        totalAmount,
        BigDecimal        totalFees
    ) {}

    public record ReconciliationSummary(
        List<StatusAggregate> transactions,
        List<StatusAggregate> payouts
    ) {}

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<StatusAggregate> toAggregates(List<Object[]> rows) {
        List<StatusAggregate> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(new StatusAggregate(
                (TransactionStatus) row[0],
                ((Number) row[1]).longValue(),
                row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO,
                row[3] != null ? (BigDecimal) row[3] : BigDecimal.ZERO
            ));
        }
        return result;
    }

    private TransactionStatusResponse toStatusResponse(Transaction t) {
        return toStatusResponse(t, null);
    }

    private TransactionStatusResponse toStatusResponse(Transaction t, EbithexPrincipal principal) {
        String phone;
        try {
            phone = encryptionService.decrypt(t.getPhoneNumber());
        } catch (Exception e) {
            log.warn("Impossible de déchiffrer le numéro pour la transaction {}", t.getId());
            phone = "****";
        }
        if (principal != null && shouldMaskPhone(principal)) {
            phone = maskPhone(phone);
        }
        return TransactionStatusResponse.builder()
            .transactionId(t.getId())
            .ebithexReference(t.getEbithexReference())
            .merchantReference(t.getMerchantReference())
            .merchantId(t.getMerchantId())
            .status(t.getStatus())
            .amount(t.getAmount())
            .currency(t.getCurrency().name())
            .phoneNumber(phone)
            .operator(t.getOperator())
            .operatorReference(t.getOperatorReference())
            .failureReason(t.getFailureReason())
            .createdAt(t.getCreatedAt())
            .updatedAt(t.getUpdatedAt())
            .build();
    }

    /**
     * Masque le numéro de téléphone pour les rôles qui n'ont pas besoin du numéro complet.
     * Ex : +22507123456 → +225071****56
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) return phone;
        // Garder le préfixe international (+XXX) + 3 premiers chiffres, masquer le milieu, garder les 2 derniers
        int prefixEnd = phone.startsWith("+") ? Math.min(6, phone.length() - 4) : Math.min(3, phone.length() - 4);
        String prefix = phone.substring(0, prefixEnd);
        String suffix = phone.substring(phone.length() - 2);
        int maskedCount = phone.length() - prefixEnd - 2;
        return prefix + "*".repeat(maskedCount) + suffix;
    }

    /** Masquer pour RECONCILIATION et FINANCE — pas pour ADMIN/SUPER_ADMIN/COUNTRY_ADMIN */
    private boolean shouldMaskPhone(EbithexPrincipal principal) {
        return principal.roles().stream().allMatch(r ->
            r == Role.RECONCILIATION || r == Role.FINANCE);
    }
}