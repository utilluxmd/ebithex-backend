package com.ebithex.payout.application;

import com.ebithex.payout.domain.BulkPayout;
import com.ebithex.payout.domain.BulkPayoutItem;
import com.ebithex.payout.dto.PayoutRequest;
import com.ebithex.payout.dto.PayoutResponse;
import com.ebithex.payout.infrastructure.BulkPayoutItemRepository;
import com.ebithex.payout.infrastructure.BulkPayoutRepository;
import com.ebithex.shared.crypto.EncryptionService;
import com.ebithex.shared.domain.BulkPaymentStatus;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.exception.DuplicateTransactionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Traitement asynchrone des lots de décaissements (bulk cash-out).
 *
 * Séparé de BulkPayoutService pour que @Async soit intercepté par le proxy Spring
 * (un bean ne peut pas appeler ses propres méthodes @Async).
 *
 * Chaque item appelle PayoutService.initiatePayout() dans sa propre transaction.
 * Un échec sur un item n'impacte pas les autres.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BulkPayoutProcessor {

    private final BulkPayoutRepository batchRepo;
    private final BulkPayoutItemRepository itemRepo;
    private final PayoutService payoutService;
    private final EncryptionService encryptionService;

    @Async
    public void processAsync(UUID batchId, UUID merchantId) {
        BulkPayout batch = batchRepo.findById(batchId).orElse(null);
        if (batch == null) {
            log.error("BulkPayout introuvable: {}", batchId);
            return;
        }

        log.info("Début traitement lot cash-out {} ({} bénéficiaires)",
            batch.getEbithexBatchReference(), batch.getTotalItems());

        batch.setStatus(BulkPaymentStatus.PROCESSING);
        batchRepo.save(batch);

        List<BulkPayoutItem> items = itemRepo.findByBulkPayoutIdOrderByItemIndex(batchId);

        for (BulkPayoutItem item : items) {
            processItem(item, merchantId);
        }

        updateBatchStatus(batch);

        log.info("Lot cash-out {} terminé — succès: {}/{}, échecs: {}/{}",
            batch.getEbithexBatchReference(),
            batch.getSuccessItems(), batch.getTotalItems(),
            batch.getFailedItems(), batch.getTotalItems());
    }

    // ─── Traitement d'un item ─────────────────────────────────────────────────

    private void processItem(BulkPayoutItem item, UUID merchantId) {
        try {
            PayoutRequest payoutReq = buildPayoutRequest(item);
            PayoutResponse response = payoutService.initiatePayout(payoutReq, merchantId);
            updateItemSuccess(item, response);

        } catch (DuplicateTransactionException e) {
            log.warn("Item {} déjà traité (doublon): {}", item.getMerchantReference(), e.getMessage());
            updateItemFailed(item, "DUPLICATE: " + e.getMessage());

        } catch (Exception e) {
            log.error("Échec décaissement item {} du lot {}: {}",
                item.getMerchantReference(), item.getBulkPayoutId(), e.getMessage());
            updateItemFailed(item, e.getMessage());
        }
    }

    @Transactional
    protected void updateItemSuccess(BulkPayoutItem item, PayoutResponse response) {
        item.setStatus(response.getStatus());
        item.setPayoutId(response.getPayoutId());
        item.setEbithexReference(response.getEbithexReference());
        if (response.getStatus() == TransactionStatus.FAILED) {
            item.setFailureReason(response.getMessage());
        }
        itemRepo.save(item);
    }

    @Transactional
    protected void updateItemFailed(BulkPayoutItem item, String reason) {
        item.setStatus(TransactionStatus.FAILED);
        item.setFailureReason(reason != null && reason.length() > 500
            ? reason.substring(0, 500) : reason);
        itemRepo.save(item);
    }

    @Transactional
    protected void updateBatchStatus(BulkPayout batch) {
        batch = batchRepo.findById(batch.getId()).orElse(batch);

        long successes  = itemRepo.countByBulkPayoutIdAndStatus(batch.getId(), TransactionStatus.SUCCESS);
        long failures   = itemRepo.countByBulkPayoutIdAndStatus(batch.getId(), TransactionStatus.FAILED);
        long processing = itemRepo.countByBulkPayoutIdAndStatus(batch.getId(), TransactionStatus.PROCESSING);

        batch.setSuccessItems((int) successes);
        batch.setFailedItems((int) failures);
        batch.setProcessedItems((int) (successes + failures));

        if (processing > 0) {
            batch.setStatus(BulkPaymentStatus.PROCESSING);
        } else if (failures == 0) {
            batch.setStatus(BulkPaymentStatus.COMPLETED);
        } else if (successes == 0) {
            batch.setStatus(BulkPaymentStatus.FAILED);
        } else {
            batch.setStatus(BulkPaymentStatus.PARTIALLY_COMPLETED);
        }

        batchRepo.save(batch);
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    private PayoutRequest buildPayoutRequest(BulkPayoutItem item) {
        PayoutRequest req = new PayoutRequest();
        req.setAmount(item.getAmount());
        req.setCurrency(item.getCurrency());
        // Déchiffrer le numéro — PayoutService le re-chiffrera dans Payout
        req.setPhoneNumber(encryptionService.decrypt(item.getPhoneNumber()));
        req.setOperator(item.getOperator());
        req.setMerchantReference(item.getMerchantReference());
        req.setDescription(item.getDescription());
        req.setBeneficiaryName(item.getBeneficiaryName());
        return req;
    }
}
