package com.ebithex.payout.application;

import com.ebithex.merchant.application.MerchantService;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.operator.outbound.OperatorDisbursementRequest;
import com.ebithex.operator.outbound.OperatorRegistry;
import com.ebithex.payment.application.OperatorGateway;
import com.ebithex.payment.infrastructure.PhoneNumberUtil;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.dto.PayoutRequest;
import com.ebithex.payout.dto.PayoutResponse;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.crypto.EncryptionService;
import com.ebithex.operatorfloat.application.OperatorFloatService;
import com.ebithex.payment.application.PaymentMetrics;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.event.PayoutStatusChangedEvent;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import com.ebithex.shared.outbox.OutboxWriter;
import com.ebithex.shared.util.ReferenceGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PayoutService {

    private final PayoutRepository         payoutRepository;
    private final OperatorRegistry         operatorRegistry;
    private final OperatorGateway          operatorGateway;
    private final PhoneNumberUtil          phoneUtil;
    private final ReferenceGenerator       referenceGenerator;
    private final MerchantService          merchantService;
    private final OutboxWriter             outboxWriter;
    private final EncryptionService        encryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentMetrics           metrics;
    private final OperatorFloatService     floatService;

    @Value("${ebithex.fees.payout-rate:0.5}")
    private double payoutFeeRate;

    @Transactional
    public PayoutResponse initiatePayout(PayoutRequest request, UUID merchantId) {

        // 1. Idempotency check — retourner le payout existant, ne pas en créer un nouveau
        Optional<Payout> existing = payoutRepository
            .findByMerchantReferenceAndMerchantId(request.getMerchantReference(), merchantId);
        if (existing.isPresent()) {
            log.info("Replay idempotent — merchantRef={} → ebithexRef={}",
                request.getMerchantReference(), existing.get().getEbithexReference());
            return toResponse(existing.get(), "Requête idempotente — payout existant");
        }

        // 2. Auto-detect operator
        OperatorType operator = request.getOperator();
        if (operator == OperatorType.AUTO) {
            operator = phoneUtil.detectOperator(request.getPhoneNumber());
            if (operator == null) {
                throw new EbithexException(ErrorCode.OPERATOR_NOT_DETECTED,
                    "Impossible de détecter l'opérateur pour: " + request.getPhoneNumber());
            }
        }
        if (!operatorRegistry.supports(operator)) {
            throw new EbithexException(ErrorCode.OPERATOR_NOT_SUPPORTED, "Opérateur non supporté: " + operator);
        }

        // 3. Fee calculation
        Merchant merchant = merchantService.findById(merchantId);
        double feeRateToApply = merchant.getCustomFeeRate() != null
            ? merchant.getCustomFeeRate().doubleValue()
            : payoutFeeRate;
        BigDecimal feeAmount = request.getAmount()
            .multiply(BigDecimal.valueOf(feeRateToApply))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal netAmount = request.getAmount().subtract(feeAmount);

        // 4. Persist payout (PENDING)
        String normalizedPhone = phoneUtil.normalizePhone(request.getPhoneNumber());
        Payout payout = Payout.builder()
            .ebithexReference(referenceGenerator.generatePayoutRef())
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
            .beneficiaryName(request.getBeneficiaryName())
            .metadata(request.getMetadata())
            .expiresAt(LocalDateTime.now().plusMinutes(30))
            .build();

        payout = payoutRepository.save(payout);
        log.info("Payout créé: {} | opérateur: {} | montant: {} | bénéficiaire: {}",
            payout.getEbithexReference(), operator, request.getAmount(), normalizedPhone);

        metrics.payoutInitiated(operator);

        // 5a. Vérifier et débiter le float opérateur
        floatService.debitFloat(operator, payout.getNetAmount(), payout.getEbithexReference());

        // 5. Call operator disbursement (via circuit breaker)
        OperatorDisbursementRequest opRequest = new OperatorDisbursementRequest(
            payout.getEbithexReference(),
            normalizedPhone,
            payout.getNetAmount(),
            payout.getCurrency(),
            operator,
            payout.getDescription(),
            null
        );
        Timer.Sample sample = metrics.startOperatorCallTimer();
        MobileMoneyOperator.OperatorInitResponse opResponse;
        try {
            opResponse = operatorGateway.initiateDisbursement(operator, opRequest);
        } finally {
            metrics.stopOperatorCallTimer(sample, operator, "disbursement");
        }

        // 6. Update status
        TransactionStatus previousStatus = payout.getStatus();
        payout.setStatus(opResponse.initialStatus());
        payout.setOperatorReference(opResponse.operatorReference());
        if (opResponse.initialStatus() == TransactionStatus.FAILED) {
            payout.setFailureReason(opResponse.message());
        }
        payoutRepository.save(payout);

        // 7. Publish event + metrics
        if (opResponse.initialStatus() == TransactionStatus.SUCCESS) {
            metrics.payoutSuccess(operator);
        } else if (opResponse.initialStatus() == TransactionStatus.FAILED) {
            metrics.payoutFailed(operator);
        }
        publishEvent(payout, opResponse.initialStatus(), previousStatus);

        return toResponse(payout, opResponse.message());
    }

    @Transactional(readOnly = true)
    public PayoutResponse getStatus(String reference, UUID merchantId) {
        Payout payout = payoutRepository.findByEbithexReference(reference)
            .filter(p -> p.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.PAYOUT_NOT_FOUND,
                "Payout introuvable: " + reference));

        if (payout.getStatus() == TransactionStatus.PROCESSING
                || payout.getStatus() == TransactionStatus.PENDING) {
            syncStatusFromOperator(payout);
        }
        return toResponse(payout, null);
    }

    @Transactional(readOnly = true)
    public Page<PayoutResponse> listPayouts(UUID merchantId, Pageable pageable) {
        return payoutRepository.findByMerchantId(merchantId, pageable)
            .map(p -> toResponse(p, null));
    }

    @Transactional
    public void processCallback(String operatorReference, TransactionStatus newStatus) {
        if (newStatus == null) return;
        payoutRepository.findByOperatorReference(operatorReference).ifPresent(payout -> {
            if (newStatus != payout.getStatus()) {
                TransactionStatus previousStatus = payout.getStatus();
                payout.setStatus(newStatus);
                payoutRepository.save(payout);
                log.info("Payout callback: {} -> {}", payout.getEbithexReference(), newStatus);
                if (newStatus == TransactionStatus.SUCCESS) metrics.payoutSuccess(payout.getOperator());
                else if (newStatus == TransactionStatus.FAILED) metrics.payoutFailed(payout.getOperator());
                publishEvent(payout, newStatus, previousStatus);
            }
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void syncStatusFromOperator(Payout payout) {
        if (payout.getOperatorReference() == null) return;
        try {
            TransactionStatus newStatus = operatorGateway.checkDisbursementStatus(
                payout.getOperator(), payout.getOperatorReference());
            if (newStatus != payout.getStatus()) {
                TransactionStatus previousStatus = payout.getStatus();
                payout.setStatus(newStatus);
                payoutRepository.save(payout);
                publishEvent(payout, newStatus, previousStatus);
            }
        } catch (Exception e) {
            log.warn("Sync statut payout échouée pour {}: {}", payout.getEbithexReference(), e.getMessage());
        }
    }

    private void publishEvent(Payout p, TransactionStatus newStatus, TransactionStatus previousStatus) {
        PayoutStatusChangedEvent event = new PayoutStatusChangedEvent(
            p.getId(), p.getEbithexReference(), p.getMerchantReference(), p.getMerchantId(),
            newStatus, previousStatus,
            p.getAmount(), p.getFeeAmount(), p.getNetAmount(), p.getCurrency(), p.getOperator()
        );
        eventPublisher.publishEvent(event);
        outboxWriter.write("Payout", p.getId(), "PayoutStatusChangedEvent", event);
    }

    private PayoutResponse toResponse(Payout p, String message) {
        return PayoutResponse.builder()
            .payoutId(p.getId())
            .ebithexReference(p.getEbithexReference())
            .merchantReference(p.getMerchantReference())
            .status(p.getStatus())
            .amount(p.getAmount())
            .feeAmount(p.getFeeAmount())
            .netAmount(p.getNetAmount())
            .currency(p.getCurrency().name())
            .phoneNumber(encryptionService.decrypt(p.getPhoneNumber()))
            .operator(p.getOperator())
            .operatorReference(p.getOperatorReference())
            .message(message)
            .failureReason(p.getFailureReason())
            .createdAt(p.getCreatedAt())
            .expiresAt(p.getExpiresAt())
            .build();
    }
}