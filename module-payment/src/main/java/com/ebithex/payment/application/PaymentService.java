package com.ebithex.payment.application;

import com.ebithex.aml.application.AmlScreeningService;
import com.ebithex.merchant.application.MerchantService;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.operator.outbound.MobileMoneyOperator.OperatorRefundResult;
import com.ebithex.operator.outbound.OperatorPaymentRequest;
import com.ebithex.operator.outbound.OperatorRegistry;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.payment.dto.PaymentRequest;
import com.ebithex.payment.dto.PaymentResponse;
import com.ebithex.payment.dto.RefundResponse;
import com.ebithex.payment.dto.TransactionStatusResponse;
import com.ebithex.wallet.application.WalletService;
import com.ebithex.payment.infrastructure.PhoneNumberUtil;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.event.PaymentStatusChangedEvent;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import com.ebithex.shared.crypto.EncryptionService;
import com.ebithex.shared.outbox.OutboxWriter;
import com.ebithex.shared.util.ReferenceGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final TransactionRepository    transactionRepository;
    private final OperatorRegistry         operatorRegistry;
    private final OperatorGateway          operatorGateway;
    private final PhoneNumberUtil          phoneUtil;
    private final ReferenceGenerator       referenceGenerator;
    private final MerchantService          merchantService;
    private final OutboxWriter             outboxWriter;
    private final EncryptionService        encryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentMetrics           metrics;
    private final TransactionLimitService  limitService;
    private final WalletService            walletService;
    private final FeeService               feeService;
    private final AmlScreeningService      amlScreeningService;

    @Timed(value = "payment.initiate",
           description = "Latence d'initiation d'un paiement Mobile Money (hors idempotent replays)",
           histogram = true,
           percentiles = {0.5, 0.95, 0.99})
    @Transactional
    public PaymentResponse initiatePayment(PaymentRequest request, UUID merchantId) {

        // 1. Idempotency check — retourner la transaction existante, ne pas en créer une nouvelle
        Optional<Transaction> existing = transactionRepository
            .findByMerchantReferenceAndMerchantId(request.getMerchantReference(), merchantId);
        if (existing.isPresent()) {
            log.info("Replay idempotent — merchantRef={} → ebithexRef={}",
                request.getMerchantReference(), existing.get().getEbithexReference());
            return toPaymentResponse(existing.get(), true);
        }

        // 2. Limit check (avant toute création — pas de limite en test mode)
        Merchant merchant = merchantService.findById(merchantId);
        limitService.checkLimits(merchant, request.getAmount());

        // 3. Auto-detect operator
        OperatorType operator = request.getOperator();
        if (operator == OperatorType.AUTO) {
            operator = phoneUtil.detectOperator(request.getPhoneNumber());
            if (operator == null) {
                // Ne pas inclure le numéro de téléphone dans le message d'erreur (PII)
                throw new EbithexException(ErrorCode.OPERATOR_NOT_DETECTED);
            }
        }
        if (!operatorRegistry.supports(operator)) {
            throw new EbithexException(ErrorCode.OPERATOR_NOT_SUPPORTED, "Opérateur non supporté: " + operator);
        }

        // 4. Fee calculation (dynamic rules from fee_rules table)
        FeeService.FeeCalculation fees = feeService.calculate(
            merchantId, operator, merchant.getCountry(), request.getAmount());
        BigDecimal feeAmount = fees.feeAmount();
        BigDecimal netAmount = fees.netAmount();

        // 5. Persist transaction (PENDING)
        String normalizedPhone = phoneUtil.normalizePhone(request.getPhoneNumber());
        Transaction transaction = Transaction.builder()
            .ebithexReference(referenceGenerator.generateEbithexRef())
            .merchantReference(request.getMerchantReference())
            .merchantId(merchantId)
            .amount(request.getAmount())
            .feeAmount(feeAmount)
            .netAmount(netAmount)
            .currency(request.getCurrency())
            .phoneNumber(encryptionService.encrypt(normalizedPhone))
            .phoneNumberIndex(encryptionService.hmacForIndex(normalizedPhone))
            .operator(operator)
            .status(TransactionStatus.PENDING)
            .description(request.getDescription())
            .customerName(request.getCustomerName())
            .customerEmail(request.getCustomerEmail())
            .metadata(request.getMetadata())
            .expiresAt(LocalDateTime.now().plusMinutes(15))
            .build();

        transaction = transactionRepository.save(transaction);
        MDC.put("transactionId", transaction.getEbithexReference());
        MDC.put("operator", operator.name());
        log.info("Transaction créée: {} | opérateur: {} | montant: {}",
            transaction.getEbithexReference(), operator, request.getAmount());

        // AML screening (après sauvegarde, dans la même transaction Spring)
        // En mode test, le screening est ignoré (dans AmlScreeningService)
        amlScreeningService.screen(transaction);

        metrics.paymentInitiated(operator);

        // 6. Call operator — simulé en sandbox, réel sinon
        MobileMoneyOperator.OperatorInitResponse opResponse;
        Timer.Sample sample = metrics.startOperatorCallTimer();
        try {
            if (SandboxContextHolder.isSandbox()) {
                opResponse = new MobileMoneyOperator.OperatorInitResponse(
                    "TEST-" + transaction.getEbithexReference(),
                    TransactionStatus.SUCCESS,
                    null, null,
                    "[TEST MODE] Transaction simulee - aucun appel operateur reel"
                );
                log.info("Test mode: transaction simulée — ref={}", transaction.getEbithexReference());
            } else {
                OperatorPaymentRequest opRequest = new OperatorPaymentRequest(
                    transaction.getEbithexReference(),
                    normalizedPhone,
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    operator,
                    transaction.getDescription(),
                    null
                );
                opResponse = operatorGateway.initiatePayment(operator, opRequest);
            }
        } finally {
            metrics.stopOperatorCallTimer(sample, operator, "payment");
        }

        // 6. Update status
        TransactionStatus previousStatus = transaction.getStatus();
        transaction.setStatus(opResponse.initialStatus());
        transaction.setOperatorReference(opResponse.operatorReference());
        if (opResponse.initialStatus() == TransactionStatus.FAILED) {
            transaction.setFailureReason(opResponse.message());
        }
        transactionRepository.save(transaction);

        // 7. Publish event + metrics
        if (opResponse.initialStatus() == TransactionStatus.SUCCESS) {
            metrics.paymentSuccess(operator);
        } else if (opResponse.initialStatus() == TransactionStatus.FAILED) {
            metrics.paymentFailed(operator);
        }
        publishEvent(transaction, opResponse.initialStatus(), previousStatus, feeAmount, netAmount);

        PaymentResponse response = toPaymentResponse(transaction, false);
        response.setOperatorReference(opResponse.operatorReference());
        response.setPaymentUrl(opResponse.paymentUrl());
        response.setUssdCode(opResponse.ussdCode());
        response.setMessage(opResponse.message());
        return response;
    }

    private PaymentResponse toPaymentResponse(Transaction t, boolean idempotentReplay) {
        return PaymentResponse.builder()
            .transactionId(t.getId())
            .ebithexReference(t.getEbithexReference())
            .merchantReference(t.getMerchantReference())
            .status(t.getStatus())
            .amount(t.getAmount())
            .feeAmount(t.getFeeAmount())
            .netAmount(t.getNetAmount())
            .currency(t.getCurrency().name())
            .phoneNumber(maskPhone(encryptionService.decrypt(t.getPhoneNumber())))
            .operator(t.getOperator())
            .operatorReference(t.getOperatorReference())
            .failureReason(t.getFailureReason())
            .idempotentReplay(idempotentReplay)
            .message(idempotentReplay ? "Requête idempotente — transaction existante" : null)
            .createdAt(t.getCreatedAt())
            .expiresAt(t.getExpiresAt())
            .build();
    }

    @Transactional(readOnly = true)
    public TransactionStatusResponse getStatus(String reference, UUID merchantId) {
        Transaction t = transactionRepository.findByEbithexReference(reference)
            .filter(tx -> tx.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction introuvable: " + reference));

        if (t.getStatus() == TransactionStatus.PROCESSING
                || t.getStatus() == TransactionStatus.PENDING) {
            syncStatusFromOperator(t);
        }
        return toStatusResponse(t);
    }

    /**
     * Vérifie si un numéro de téléphone a déjà effectué une transaction chez ce marchand.
     *
     * <p>Utilise l'index HMAC-SHA256 : le numéro en clair n'est jamais stocké ni transmis en base.
     * Utile pour les marchands souhaitant identifier les clients récurrents ou bloquer des numéros.
     *
     * @param phoneNumber Numéro au format E.164 (ex: +22507XXXXXXXX)
     * @param merchantId  Périmètre du marchand demandeur
     * @return true si au moins une transaction existe pour ce numéro chez ce marchand
     */
    @Transactional(readOnly = true)
    public boolean checkPhoneExists(String phoneNumber, UUID merchantId) {
        String normalized = phoneUtil.normalizePhone(phoneNumber);
        String hmac = encryptionService.hmacForIndex(normalized);
        return transactionRepository.existsByMerchantIdAndPhoneNumberIndex(merchantId, hmac);
    }

    @Transactional(readOnly = true)
    public Page<TransactionStatusResponse> listTransactions(UUID merchantId, Pageable pageable) {
        return transactionRepository.findByMerchantId(merchantId, pageable)
            .map(this::toStatusResponse);
    }

    /**
     * Rembourse une transaction SUCCESS ou PARTIALLY_REFUNDED.
     *
     * Flux :
     *  1. Valide que la transaction appartient au marchand et est remboursable
     *  2. Calcule le montant restant à rembourser
     *  3. Tentative de reversal opérateur (best-effort, uniquement pour remboursement total)
     *  4. Débite le wallet marchand du montant remboursé
     *  5. Met à jour refundedAmount, passe en PARTIALLY_REFUNDED ou REFUNDED
     *  6. Publie PaymentStatusChangedEvent → webhook REFUND_COMPLETED
     *
     * @param requestedAmount Montant à rembourser, null = remboursement total du restant
     * @throws EbithexException TRANSACTION_NOT_FOUND, REFUND_NOT_ALLOWED, INVALID_AMOUNT
     */
    @Transactional
    public RefundResponse refundPayment(String ebithexReference, UUID merchantId, BigDecimal requestedAmount) {
        Transaction t = transactionRepository.findByEbithexReference(ebithexReference)
            .filter(tx -> tx.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction introuvable: " + ebithexReference));

        if (t.getStatus() != TransactionStatus.SUCCESS
                && t.getStatus() != TransactionStatus.PARTIALLY_REFUNDED) {
            throw new EbithexException(ErrorCode.REFUND_NOT_ALLOWED,
                "Seules les transactions SUCCESS/PARTIALLY_REFUNDED peuvent être remboursées. Statut actuel: "
                    + t.getStatus());
        }

        // Calcul du montant restant à rembourser
        BigDecimal alreadyRefunded = t.getRefundedAmount() != null
            ? t.getRefundedAmount() : BigDecimal.ZERO;
        BigDecimal remaining = t.getAmount().subtract(alreadyRefunded);

        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new EbithexException(ErrorCode.REFUND_NOT_ALLOWED,
                "Transaction déjà intégralement remboursée: " + ebithexReference);
        }

        BigDecimal actualAmount;
        if (requestedAmount == null || requestedAmount.compareTo(remaining) >= 0) {
            actualAmount = remaining; // remboursement total du restant
        } else {
            if (requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new EbithexException(ErrorCode.INVALID_AMOUNT,
                    "Le montant du remboursement doit être strictement positif");
            }
            actualAmount = requestedAmount;
        }

        boolean isFullRefund = actualAmount.compareTo(remaining) >= 0;
        TransactionStatus newStatus = isFullRefund
            ? TransactionStatus.REFUNDED : TransactionStatus.PARTIALLY_REFUNDED;

        // Étape 1 : Reversal opérateur — uniquement pour les remboursements totaux
        // (les opérateurs ne supportent pas le reversal partiel en général)
        if (isFullRefund && t.getOperatorReference() != null && !SandboxContextHolder.isSandbox()
                && operatorRegistry.get(t.getOperator()).supportsReversal()) {
            try {
                OperatorRefundResult refundResult = operatorGateway.reversePayment(
                    t.getOperator(), t.getOperatorReference(),
                    t.getAmount(), t.getCurrency().name());

                if (refundResult.success()) {
                    t.setOperatorRefundReference(refundResult.operatorRefundReference());
                    log.info("Reversal opérateur OK: {} | refundRef={}",
                        ebithexReference, refundResult.operatorRefundReference());
                } else {
                    log.warn("Reversal opérateur échoué (best-effort): {} | {}",
                        ebithexReference, refundResult.message());
                }
            } catch (Exception e) {
                log.warn("Reversal opérateur exception (best-effort): {} | {}",
                    ebithexReference, e.getMessage());
            }
        } else if (t.getOperatorReference() != null && !SandboxContextHolder.isSandbox() && isFullRefund) {
            log.info("Reversal non supporté par {} — remboursement comptable uniquement: {}",
                t.getOperator(), ebithexReference);
        }

        // Étape 2 : Débit wallet du montant net proportionnel (le marchand a reçu netAmount, pas le montant brut)
        BigDecimal walletDebitAmount = t.getNetAmount() != null && t.getAmount().compareTo(BigDecimal.ZERO) > 0
            ? actualAmount.multiply(t.getNetAmount()).divide(t.getAmount(), 2, java.math.RoundingMode.HALF_UP)
            : actualAmount;
        walletService.debitRefund(merchantId, walletDebitAmount, t.getEbithexReference(), t.getCurrency());

        TransactionStatus previousStatus = t.getStatus();
        BigDecimal newRefunded = alreadyRefunded.add(actualAmount);
        t.setRefundedAmount(newRefunded);
        t.setStatus(newStatus);
        transactionRepository.save(t);

        log.info("Transaction remboursée: {} | montant={} | status={} | merchant={}",
            ebithexReference, actualAmount, newStatus, merchantId);

        publishEvent(t, newStatus, previousStatus, t.getFeeAmount(), t.getNetAmount());

        return new RefundResponse(
            t.getId(), t.getEbithexReference(), t.getMerchantReference(),
            t.getAmount(), newRefunded, t.getAmount().subtract(newRefunded),
            t.getCurrency().name(), newStatus, t.getUpdatedAt()
        );
    }

    /**
     * Annule une transaction PENDING à la demande du marchand.
     *
     * Seule une transaction en statut PENDING peut être annulée.
     * Une transaction déjà soumise à l'opérateur (PROCESSING, SUCCESS…) ne peut plus l'être.
     *
     * @throws EbithexException TRANSACTION_NOT_FOUND si introuvable ou hors périmètre
     * @throws EbithexException CANCEL_NOT_ALLOWED si le statut interdit l'annulation
     */
    @Transactional
    public TransactionStatusResponse cancelPayment(String ebithexReference, UUID merchantId) {
        Transaction t = transactionRepository.findByEbithexReference(ebithexReference)
            .filter(tx -> tx.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction introuvable: " + ebithexReference));

        if (!t.getStatus().canTransitionTo(TransactionStatus.CANCELLED)) {
            throw new EbithexException(ErrorCode.CANCEL_NOT_ALLOWED,
                "Transaction non annulable depuis le statut actuel: " + t.getStatus());
        }

        TransactionStatus previousStatus = t.getStatus();
        t.setStatus(TransactionStatus.CANCELLED);
        transactionRepository.save(t);

        log.info("Transaction annulée: {} | merchant={}", ebithexReference, merchantId);
        publishEvent(t, TransactionStatus.CANCELLED, previousStatus, t.getFeeAmount(), t.getNetAmount());

        return toStatusResponse(t);
    }

    @Transactional
    public void processCallback(String operatorReference, TransactionStatus newStatus) {
        if (newStatus == null) return;
        transactionRepository.findByOperatorReference(operatorReference).ifPresent(t -> {
            if (newStatus != t.getStatus()) {
                TransactionStatus previousStatus = t.getStatus();
                t.setStatus(newStatus);
                transactionRepository.save(t);
                log.info("Callback: {} -> {}", t.getEbithexReference(), newStatus);
                if (newStatus == TransactionStatus.SUCCESS) metrics.paymentSuccess(t.getOperator());
                else if (newStatus == TransactionStatus.FAILED) metrics.paymentFailed(t.getOperator());
                publishEvent(t, newStatus, previousStatus, t.getFeeAmount(), t.getNetAmount());
            }
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void syncStatusFromOperator(Transaction t) {
        if (t.getOperatorReference() == null) return;
        try {
            TransactionStatus newStatus = operatorGateway.checkPaymentStatus(
                t.getOperator(), t.getOperatorReference());
            if (newStatus != null && newStatus != t.getStatus()) {
                TransactionStatus previousStatus = t.getStatus();
                t.setStatus(newStatus);
                transactionRepository.save(t);
                publishEvent(t, newStatus, previousStatus, t.getFeeAmount(), t.getNetAmount());
            }
        } catch (Exception e) {
            log.warn("Sync statut échouée pour {}: {}", t.getEbithexReference(), e.getMessage());
        }
    }

    private void publishEvent(Transaction t, TransactionStatus newStatus,
                              TransactionStatus previousStatus,
                              BigDecimal feeAmount, BigDecimal netAmount) {
        PaymentStatusChangedEvent event = new PaymentStatusChangedEvent(
            t.getId(), t.getEbithexReference(), t.getMerchantReference(), t.getMerchantId(),
            newStatus, previousStatus,
            t.getAmount(), feeAmount, netAmount, t.getCurrency(), t.getOperator()
        );
        eventPublisher.publishEvent(event);
        outboxWriter.write("Transaction", t.getId(), "PaymentStatusChangedEvent", event);
    }

    private TransactionStatusResponse toStatusResponse(Transaction t) {
        return TransactionStatusResponse.builder()
            .transactionId(t.getId())
            .ebithexReference(t.getEbithexReference())
            .merchantReference(t.getMerchantReference())
            .status(t.getStatus())
            .amount(t.getAmount())
            .currency(t.getCurrency().name())
            .phoneNumber(maskPhone(encryptionService.decrypt(t.getPhoneNumber())))
            .operator(t.getOperator())
            .operatorReference(t.getOperatorReference())
            .failureReason(t.getFailureReason())
            .createdAt(t.getCreatedAt())
            .updatedAt(t.getUpdatedAt())
            .build();
    }

    /**
     * Masque partiellement un numéro de téléphone E.164 pour la réponse API.
     *
     * <p>Conserve le préfixe (indicatif pays + début) et les 4 derniers chiffres.
     * Exemple : {@code +22507001234} → {@code +225****1234}
     *
     * <p>Minimisation des données PII dans les réponses API, conformément au RGPD art. 5.1(c).
     * Évite l'exposition en clair dans les logs HTTP, traces OpenTelemetry et systèmes tiers.
     *
     * @param phone numéro décrypté en format E.164, ou {@code null}
     * @return numéro masqué, ou {@code null} si l'entrée est {@code null}
     */
    static String maskPhone(String phone) {
        if (phone == null) return null;
        if (phone.length() <= 6) return "****";
        // Conserve jusqu'à 4 chars de préfixe (ex. "+225") + **** + 4 derniers chiffres
        int prefixLen = Math.min(4, phone.length() - 4);
        return phone.substring(0, prefixLen) + "****" + phone.substring(phone.length() - 4);
    }
}