package com.ebithex.payment.api;

import com.ebithex.payment.dto.TransactionStatusResponse;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.dto.PayoutSummaryResponse;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.crypto.EncryptionService;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Endpoints support client back-office.
 *
 * Accès (défini dans SecurityConfig) :
 *   /internal/support/** → SUPPORT, COUNTRY_ADMIN, ADMIN, SUPER_ADMIN
 */
@RestController
@RequestMapping("/internal/support")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Back-office — Support")
public class SupportController {

    private final TransactionRepository transactionRepository;
    private final PayoutRepository      payoutRepository;
    private final EncryptionService     encryptionService;

    @GetMapping("/transactions/{reference}")
    @Operation(summary = "Détail d'une transaction par référence Ebithex (tous marchands)")
    public ResponseEntity<ApiResponse<TransactionStatusResponse>> getTransaction(
            @PathVariable String reference) {
        return transactionRepository.findByEbithexReference(reference)
            .map(t -> ResponseEntity.ok(ApiResponse.ok(toStatusResponse(t))))
            .orElseThrow(() -> new EbithexException(ErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction introuvable: " + reference));
    }

    @GetMapping("/payouts/{reference}")
    @Operation(summary = "Détail d'un décaissement par référence Ebithex (tous marchands)")
    public ResponseEntity<ApiResponse<PayoutSummaryResponse>> getPayout(
            @PathVariable String reference) {
        return payoutRepository.findByEbithexReference(reference)
            .map(p -> ResponseEntity.ok(ApiResponse.ok(PayoutSummaryResponse.from(p))))
            .orElseThrow(() -> new EbithexException(ErrorCode.PAYOUT_NOT_FOUND,
                "Payout introuvable: " + reference));
    }

    @GetMapping("/merchants/{merchantId}/transactions")
    @Operation(summary = "Toutes les transactions d'un marchand (paginated)")
    public ResponseEntity<ApiResponse<Page<TransactionStatusResponse>>> getMerchantTransactions(
            @PathVariable UUID merchantId,
            Pageable pageable) {
        Page<TransactionStatusResponse> page = transactionRepository
            .findByMerchantId(merchantId, pageable)
            .map(this::toStatusResponse);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/merchants/{merchantId}/payouts")
    @Operation(summary = "Tous les décaissements d'un marchand (paginated)")
    public ResponseEntity<ApiResponse<Page<PayoutSummaryResponse>>> getMerchantPayouts(
            @PathVariable UUID merchantId,
            Pageable pageable) {
        Page<PayoutSummaryResponse> page = payoutRepository
            .findByMerchantId(merchantId, pageable)
            .map(PayoutSummaryResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    private TransactionStatusResponse toStatusResponse(com.ebithex.payment.domain.Transaction t) {
        return TransactionStatusResponse.builder()
            .transactionId(t.getId())
            .ebithexReference(t.getEbithexReference())
            .merchantReference(t.getMerchantReference())
            .status(t.getStatus())
            .amount(t.getAmount())
            .currency(t.getCurrency().name())
            .phoneNumber(encryptionService.decrypt(t.getPhoneNumber()))
            .operator(t.getOperator())
            .operatorReference(t.getOperatorReference())
            .failureReason(t.getFailureReason())
            .createdAt(t.getCreatedAt())
            .updatedAt(t.getUpdatedAt())
            .build();
    }
}