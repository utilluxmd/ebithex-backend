package com.ebithex.wallet.api;

import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.wallet.application.WalletService;
import com.ebithex.wallet.dto.WalletResponse;
import com.ebithex.wallet.dto.WalletTransactionResponse;
import com.ebithex.wallet.dto.WithdrawalRequest;
import com.ebithex.wallet.dto.WithdrawalResponse;
import com.ebithex.wallet.dto.WithdrawalSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Soldes, transactions et retraits du wallet marchand")
@SecurityRequirement(name = "BearerAuth")
@SecurityRequirement(name = "ApiKeyAuth")
public class WalletController {

    private final WalletService walletService;

    /**
     * GET /v1/wallet
     * Retourne tous les wallets du marchand (toutes devises actives).
     */
    @GetMapping("")
    @Operation(summary = "Soldes de tous les wallets du marchand")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<List<WalletResponse>>> getBalances(
            @AuthenticationPrincipal EbithexPrincipal principal) {

        List<WalletResponse> balances = walletService.getBalances(principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(balances));
    }

    /**
     * GET /v1/wallet/balance?currency=XOF
     * Retourne le solde dans une devise spécifique.
     */
    @GetMapping("/balance")
    @Operation(summary = "Solde dans une devise spécifique")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<WalletResponse>> getBalance(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @RequestParam(defaultValue = "XOF") Currency currency) {

        WalletResponse balance = walletService.getBalance(principal.merchantId(), currency);
        return ResponseEntity.ok(ApiResponse.ok(balance));
    }

    /**
     * GET /v1/wallet/transactions
     * Retourne le grand livre du wallet (paginé, ordre antichronologique).
     */
    @GetMapping("/transactions")
    @Operation(summary = "Grand livre du wallet (paginé)")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<Page<WalletTransactionResponse>>> getTransactions(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<WalletTransactionResponse> page =
            walletService.getTransactions(principal.merchantId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    /**
     * POST /v1/wallet/withdrawals
     * Soumet une demande de retrait (statut PENDING — en attente de validation finance).
     * Le wallet n'est pas débité immédiatement.
     * Seuls les marchands KYC vérifiés peuvent retirer.
     */
    @PostMapping("/withdrawals")
    @Operation(summary = "Soumettre une demande de retrait", description = "Requiert le rôle MERCHANT_KYC_VERIFIED.")
    @PreAuthorize("hasRole('MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<WithdrawalResponse>> requestWithdrawal(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @Valid @RequestBody WithdrawalRequest request) {

        String reference = "WD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Currency currency = request.getCurrency() != null ? request.getCurrency() : Currency.XOF;
        WithdrawalResponse response = walletService.requestWithdrawal(
            principal.merchantId(),
            request.getAmount(),
            reference,
            request.getDescription(),
            currency);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * GET /v1/wallet/withdrawals
     * Historique des demandes de retrait du marchand authentifié.
     */
    @GetMapping("/withdrawals")
    @Operation(summary = "Historique des demandes de retrait")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<Page<WithdrawalSummaryResponse>>> listWithdrawals(
            @AuthenticationPrincipal EbithexPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<WithdrawalSummaryResponse> page =
            walletService.listMerchantWithdrawals(principal.merchantId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }
}