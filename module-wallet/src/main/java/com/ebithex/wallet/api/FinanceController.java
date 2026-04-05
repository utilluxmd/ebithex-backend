package com.ebithex.wallet.api;

import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.audit.AuditLogService;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.wallet.application.WalletService;
import com.ebithex.wallet.domain.WithdrawalStatus;
import com.ebithex.wallet.dto.WalletResponse;
import com.ebithex.wallet.dto.WithdrawalSummaryResponse;
import com.ebithex.wallet.infrastructure.WalletRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints finance back-office — vue sur les wallets marchands.
 *
 * Accès (défini dans SecurityConfig) :
 *   /internal/finance/** → FINANCE, ADMIN, SUPER_ADMIN
 */
@RestController("walletFinanceController")
@RequestMapping("/internal/finance")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Back-office — Finance")
public class FinanceController {

    private final WalletRepository walletRepository;
    private final WalletService    walletService;
    private final AuditLogService  auditLogService;

    @GetMapping("/wallets")
    @Operation(summary = "Lister tous les wallets marchands (paginated)")
    public ResponseEntity<ApiResponse<Page<WalletResponse>>> listWallets(Pageable pageable) {
        Page<WalletResponse> page = walletRepository.findAll(pageable).map(WalletResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/wallets/{merchantId}")
    @Operation(summary = "Wallets d'un marchand (toutes devises)")
    public ResponseEntity<ApiResponse<List<WalletResponse>>> getWallet(@PathVariable UUID merchantId) {
        List<WalletResponse> wallets = walletRepository.findByMerchantId(merchantId)
            .stream().map(WalletResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(wallets));
    }

    @GetMapping("/summary")
    @Operation(summary = "Résumé agrégé des balances (total disponible, en attente, nombre de marchands)")
    public ResponseEntity<ApiResponse<FinanceSummary>> getSummary() {
        List<WalletResponse> all = walletRepository.findAll().stream()
            .map(WalletResponse::from).toList();

        BigDecimal totalAvailable = all.stream()
            .map(WalletResponse::availableBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPending = all.stream()
            .map(WalletResponse::pendingBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(ApiResponse.ok(
            new FinanceSummary(totalAvailable, totalPending, all.size())));
    }

    // ── Gestion des retraits ──────────────────────────────────────────────────

    @GetMapping("/withdrawals")
    @Operation(summary = "Lister les demandes de retrait (filtrable par statut)")
    public ResponseEntity<ApiResponse<Page<WithdrawalSummaryResponse>>> listWithdrawals(
            @RequestParam(required = false) WithdrawalStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(walletService.listWithdrawals(status, pageable)));
    }

    @PutMapping("/withdrawals/{id}/approve")
    @Operation(summary = "Approuver une demande de retrait — débite le wallet marchand")
    public ResponseEntity<ApiResponse<WithdrawalSummaryResponse>> approveWithdrawal(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        WithdrawalSummaryResponse response = walletService.approveWithdrawal(id, principal.id());
        auditLogService.record("WITHDRAWAL_APPROVED", "WithdrawalRequest", id.toString(), null);
        return ResponseEntity.ok(ApiResponse.ok("Retrait approuvé", response));
    }

    @PutMapping("/withdrawals/{id}/reject")
    @Operation(summary = "Rejeter une demande de retrait")
    public ResponseEntity<ApiResponse<WithdrawalSummaryResponse>> rejectWithdrawal(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal,
            @RequestBody Map<String, String> body) {
        WithdrawalSummaryResponse response =
            walletService.rejectWithdrawal(id, principal.id(), body.get("reason"));
        auditLogService.record("WITHDRAWAL_REJECTED", "WithdrawalRequest", id.toString(),
            "{\"reason\":\"" + (body.get("reason") != null ? body.get("reason").replace("\"","\\\"") : "") + "\"}");
        return ResponseEntity.ok(ApiResponse.ok("Retrait rejeté", response));
    }

    @PutMapping("/withdrawals/{id}/execute")
    @Operation(summary = "Confirmer l'exécution d'un retrait (transfert bancaire effectué)")
    public ResponseEntity<ApiResponse<WithdrawalSummaryResponse>> executeWithdrawal(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        WithdrawalSummaryResponse response = walletService.markWithdrawalExecuted(id, principal.id());
        auditLogService.record("WITHDRAWAL_EXECUTED", "WithdrawalRequest", id.toString(), null);
        return ResponseEntity.ok(ApiResponse.ok("Retrait marqué exécuté", response));
    }

    public record FinanceSummary(
        BigDecimal totalAvailableBalance,
        BigDecimal totalPendingBalance,
        int        merchantCount
    ) {}
}