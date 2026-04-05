package com.ebithex.payout.application;

import com.ebithex.payment.infrastructure.PhoneNumberUtil;
import com.ebithex.payout.domain.BulkPayout;
import com.ebithex.payout.domain.BulkPayoutItem;
import com.ebithex.payout.dto.BulkPayoutItemRequest;
import com.ebithex.payout.dto.BulkPayoutRequest;
import com.ebithex.payout.dto.BulkPayoutResponse;
import com.ebithex.payout.infrastructure.BulkPayoutItemRepository;
import com.ebithex.payout.infrastructure.BulkPayoutRepository;
import com.ebithex.shared.crypto.EncryptionService;
import com.ebithex.shared.domain.BulkPaymentStatus;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.exception.DuplicateTransactionException;
import com.ebithex.shared.util.ReferenceGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BulkPayoutService {

    private final BulkPayoutRepository batchRepo;
    private final BulkPayoutItemRepository itemRepo;
    private final BulkPayoutProcessor processor;
    private final ReferenceGenerator referenceGenerator;
    private final PhoneNumberUtil phoneUtil;
    private final EncryptionService encryptionService;

    /**
     * Crée un lot de décaissements et déclenche le traitement asynchrone.
     *
     * 1. Valide le lot (doublons internes)
     * 2. Persiste BulkPayout + BulkPayoutItems (statut PENDING)
     * 3. Déclenche processAsync APRÈS commit (TransactionSynchronization)
     * 4. Retourne immédiatement avec la référence du lot
     */
    @Transactional
    public BulkPayoutResponse createBulk(BulkPayoutRequest request, UUID merchantId) {

        // Vérifier doublon au niveau du lot
        batchRepo.findByMerchantBatchReferenceAndMerchantId(
            request.getMerchantBatchReference(), merchantId
        ).ifPresent(b -> {
            throw new DuplicateTransactionException(
                "Lot déjà existant: " + b.getEbithexBatchReference());
        });

        // Détecter les doublons internes (même merchantReference dans le même lot)
        long distinctRefs = request.getItems().stream()
            .map(BulkPayoutItemRequest::getMerchantReference)
            .distinct().count();
        if (distinctRefs < request.getItems().size()) {
            throw new EbithexException(ErrorCode.DUPLICATE_MERCHANT_REFERENCE,
                "Le lot contient des références marchands en doublon");
        }

        // Créer le lot
        BulkPayout batch = BulkPayout.builder()
            .ebithexBatchReference(referenceGenerator.generateBulkPayoutRef())
            .merchantBatchReference(request.getMerchantBatchReference())
            .merchantId(merchantId)
            .label(request.getLabel())
            .totalItems(request.getItems().size())
            .build();

        batch = batchRepo.save(batch);
        log.info("Lot cash-out créé: {} | {} bénéficiaires",
            batch.getEbithexBatchReference(), batch.getTotalItems());

        // Créer les items
        List<BulkPayoutItem> items = new ArrayList<>();
        for (int i = 0; i < request.getItems().size(); i++) {
            BulkPayoutItemRequest itemReq = request.getItems().get(i);

            OperatorType operator = itemReq.getOperator();
            if (operator == OperatorType.AUTO) {
                OperatorType detected = phoneUtil.detectOperator(itemReq.getPhoneNumber());
                if (detected != null) operator = detected;
            }

            String normalizedPhone = phoneUtil.normalizePhone(itemReq.getPhoneNumber());

            BulkPayoutItem item = BulkPayoutItem.builder()
                .bulkPayoutId(batch.getId())
                .itemIndex(i)
                .merchantReference(itemReq.getMerchantReference())
                .phoneNumber(encryptionService.encrypt(normalizedPhone))
                .phoneNumberIndex(encryptionService.hmacForIndex(normalizedPhone))
                .amount(itemReq.getAmount())
                .currency(itemReq.getCurrency())
                .operator(operator)
                .description(itemReq.getDescription())
                .beneficiaryName(itemReq.getBeneficiaryName())
                .build();

            items.add(item);
        }
        itemRepo.saveAll(items);

        // Déclencher le traitement async APRÈS commit — garantit que les items sont en DB
        final UUID batchId = batch.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                processor.processAsync(batchId, merchantId);
            }
        });

        return toBatchResponse(batch, null);
    }

    @Transactional(readOnly = true)
    public BulkPayoutResponse getBatch(String batchReference, UUID merchantId) {
        BulkPayout batch = batchRepo.findByEbithexBatchReference(batchReference)
            .filter(b -> b.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.BULK_PAYOUT_NOT_FOUND,
                "Lot introuvable: " + batchReference));

        return toBatchResponse(batch, null);
    }

    @Transactional(readOnly = true)
    public BulkPayoutResponse getBatchItems(String batchReference, UUID merchantId) {
        BulkPayout batch = batchRepo.findByEbithexBatchReference(batchReference)
            .filter(b -> b.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.BULK_PAYOUT_NOT_FOUND,
                "Lot introuvable: " + batchReference));

        List<BulkPayoutItem> items = itemRepo.findByBulkPayoutIdOrderByItemIndex(batch.getId());
        List<BulkPayoutResponse.ItemSummary> summaries = items.stream()
            .map(this::toItemSummary)
            .collect(Collectors.toList());

        return toBatchResponse(batch, summaries);
    }

    @Transactional(readOnly = true)
    public Page<BulkPayoutResponse> listBatches(UUID merchantId, Pageable pageable) {
        return batchRepo.findByMerchantId(merchantId, pageable)
            .map(b -> toBatchResponse(b, null));
    }

    // ─── Mappers ─────────────────────────────────────────────────────────────

    private BulkPayoutResponse toBatchResponse(BulkPayout b, List<BulkPayoutResponse.ItemSummary> items) {
        return BulkPayoutResponse.builder()
            .batchId(b.getId())
            .ebithexBatchReference(b.getEbithexBatchReference())
            .merchantBatchReference(b.getMerchantBatchReference())
            .label(b.getLabel())
            .status(b.getStatus())
            .totalItems(b.getTotalItems())
            .processedItems(b.getProcessedItems())
            .successItems(b.getSuccessItems())
            .failedItems(b.getFailedItems())
            .createdAt(b.getCreatedAt())
            .items(items)
            .build();
    }

    private BulkPayoutResponse.ItemSummary toItemSummary(BulkPayoutItem item) {
        return BulkPayoutResponse.ItemSummary.builder()
            .itemIndex(item.getItemIndex())
            .merchantReference(item.getMerchantReference())
            .phoneNumber(encryptionService.decrypt(item.getPhoneNumber()))
            .beneficiaryName(item.getBeneficiaryName())
            .amount(item.getAmount())
            .currency(item.getCurrency() != null ? item.getCurrency().name() : null)
            .operator(item.getOperator())
            .status(item.getStatus())
            .ebithexReference(item.getEbithexReference())
            .failureReason(item.getFailureReason())
            .build();
    }
}