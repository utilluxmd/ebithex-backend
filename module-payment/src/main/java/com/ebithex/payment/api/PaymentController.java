package com.ebithex.payment.api;

import com.ebithex.payment.application.PaymentService;
import com.ebithex.payment.dto.PaymentRequest;
import com.ebithex.payment.dto.PaymentResponse;
import com.ebithex.payment.dto.RefundRequest;
import com.ebithex.payment.dto.RefundResponse;
import com.ebithex.payment.dto.TransactionStatusResponse;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Validated
@Tag(name = "Paiements", description = "Initiation et suivi des paiements Mobile Money")
@SecurityRequirement(name = "ApiKeyAuth")
@SecurityRequirement(name = "BearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_WRITE)")
    @Operation(summary = "Initier un paiement",
               description = "Initie un paiement Mobile Money. L'opérateur est détecté automatiquement si operator=AUTO. " +
                             "Envoyer le même merchantReference retourne la transaction originale (idempotent).")
    public ResponseEntity<ApiResponse<PaymentResponse>> initiatePayment(
            @Valid @RequestBody PaymentRequest request,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        PaymentResponse response = paymentService.initiatePayment(request, principal.merchantId());
        ResponseEntity.BodyBuilder builder = response.isIdempotentReplay()
            ? ResponseEntity.ok().header("Idempotent-Replayed", "true")
            : ResponseEntity.status(HttpStatus.CREATED)
                            .header("Location", "/api/v1/payments/" + response.getEbithexReference());
        return builder.body(ApiResponse.ok("Paiement initié", response));
    }

    @GetMapping("/{reference}")
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_READ)")
    @Operation(summary = "Statut d'une transaction")
    public ResponseEntity<ApiResponse<TransactionStatusResponse>> getStatus(
            @PathVariable String reference,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        TransactionStatusResponse status = paymentService.getStatus(reference, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(status));
    }

    @PostMapping("/{reference}/refund")
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_WRITE)")
    @Operation(summary = "Rembourser une transaction",
               description = "Disponible pour les transactions SUCCESS et PARTIALLY_REFUNDED. " +
                             "Si `amount` est omis ou dépasse le restant dû, remboursement total. " +
                             "Si `amount` < restant, remboursement partiel → statut PARTIALLY_REFUNDED. " +
                             "Débite le wallet marchand et émet le webhook REFUND_COMPLETED.")
    public ResponseEntity<ApiResponse<RefundResponse>> refund(
            @PathVariable String reference,
            @Valid @RequestBody(required = false) RefundRequest body,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        RefundResponse response = paymentService.refundPayment(
            reference, principal.merchantId(),
            body != null ? body.amount() : null);
        return ResponseEntity.ok(ApiResponse.ok("Remboursement initié", response));
    }

    @PostMapping("/{reference}/cancel")
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_WRITE)")
    @Operation(summary = "Annuler une transaction PENDING",
               description = "Annule une transaction en statut PENDING avant tout traitement opérateur. " +
                             "Retourne 400 si la transaction n'est plus annulable.")
    public ResponseEntity<ApiResponse<TransactionStatusResponse>> cancel(
            @PathVariable String reference,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        TransactionStatusResponse response = paymentService.cancelPayment(reference, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok("Transaction annulée", response));
    }

    @GetMapping("/phone-check")
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_READ)")
    @Operation(
        summary = "Vérifier l'existence d'un numéro",
        description = "Indique si un numéro de téléphone a déjà effectué une transaction chez ce marchand. " +
                      "Le numéro n'est jamais stocké en clair : la vérification utilise l'index HMAC-SHA256. " +
                      "Utile pour identifier les clients récurrents sans exposer les données PII."
    )
    public ResponseEntity<ApiResponse<Boolean>> checkPhoneExists(
            @RequestParam String phoneNumber,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        boolean exists = paymentService.checkPhoneExists(phoneNumber, principal.merchantId());
        return ResponseEntity.ok(ApiResponse.ok(exists));
    }

    @GetMapping
    @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_READ)")
    @Operation(summary = "Lister les transactions")
    public ResponseEntity<ApiResponse<Page<TransactionStatusResponse>>> listTransactions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<TransactionStatusResponse> result = paymentService.listTransactions(principal.merchantId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
