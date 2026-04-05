package com.ebithex.settlement.application;

import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.settlement.domain.SettlementBatch;
import com.ebithex.settlement.domain.SettlementBatchStatus;
import com.ebithex.settlement.domain.SettlementEntry;
import com.ebithex.settlement.domain.SettlementEntryType;
import com.ebithex.settlement.infrastructure.SettlementBatchRepository;
import com.ebithex.settlement.infrastructure.SettlementEntryRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service de règlement (settlement) — calcul des montants dus aux opérateurs.
 *
 * Un cycle de règlement couvre une période (généralement J-1, 00:00 → 23:59).
 * Pour chaque opérateur actif, un SettlementBatch est créé avec :
 *  - grossAmount : somme des montants bruts des transactions SUCCESS
 *  - feeAmount   : somme des frais Ebithex
 *  - netAmount   : montant net dû à l'opérateur = gross - fee
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SettlementService {

    private static final DateTimeFormatter REF_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final SettlementBatchRepository batchRepository;
    private final SettlementEntryRepository entryRepository;
    private final TransactionRepository     transactionRepository;

    /**
     * Génère les batches de règlement pour tous les opérateurs sur la période donnée.
     * Si un batch existe déjà pour (operator, currency, periodStart), il est ignoré (idempotent).
     *
     * @return nombre de batches créés
     */
    @Transactional
    public int runSettlementCycle(LocalDateTime periodStart, LocalDateTime periodEnd) {
        log.info("Démarrage cycle de règlement: {} → {}", periodStart, periodEnd);
        int created = 0;

        for (OperatorType operator : OperatorType.values()) {
            List<Transaction> transactions = transactionRepository
                .findSuccessForSettlement(operator, periodStart, periodEnd);

            if (transactions.isEmpty()) continue;

            // Grouper par devise
            var byCurrency = new java.util.HashMap<String, List<Transaction>>();
            for (Transaction tx : transactions) {
                String currency = tx.getCurrency() != null ? tx.getCurrency().name() : "XOF";
                byCurrency.computeIfAbsent(currency, k -> new ArrayList<>()).add(tx);
            }

            for (var entry : byCurrency.entrySet()) {
                String currency = entry.getKey();
                List<Transaction> txList = entry.getValue();

                // Idempotency check — utilise la batchReference calculée (unique constraint DB)
                String ref = computeBatchReference(operator, currency, periodStart);
                if (batchRepository.findByBatchReference(ref).isPresent()) {
                    log.debug("Batch déjà existant: {}", ref);
                    continue;
                }

                SettlementBatch batch = buildBatch(operator, currency, periodStart, periodEnd, txList);
                batch = batchRepository.save(batch);
                saveEntries(batch, txList);
                created++;

                log.info("Batch de règlement créé: {} | operator={} | currency={} | {} txs | net={}",
                    batch.getBatchReference(), operator, currency,
                    batch.getTransactionCount(), batch.getNetAmount());
            }
        }

        log.info("Cycle de règlement terminé: {} batch(es) créé(s)", created);
        return created;
    }

    /** Marque un batch comme SETTLED (après confirmation bancaire). */
    @Transactional
    public SettlementBatch markSettled(UUID batchId) {
        SettlementBatch batch = batchRepository.findById(batchId)
            .orElseThrow(() -> new EbithexException(ErrorCode.SETTLEMENT_NOT_FOUND, "Batch introuvable: " + batchId));
        if (batch.getStatus() != SettlementBatchStatus.PENDING &&
            batch.getStatus() != SettlementBatchStatus.PROCESSING) {
            throw new EbithexException(ErrorCode.SETTLEMENT_INVALID_STATUS,
                "Impossible de confirmer un batch en statut: " + batch.getStatus());
        }
        batch.setStatus(SettlementBatchStatus.SETTLED);
        batch.setSettledAt(LocalDateTime.now());
        return batchRepository.save(batch);
    }

    @Transactional(readOnly = true)
    public Page<SettlementBatch> listBatches(
            OperatorType operator, SettlementBatchStatus status,
            LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return batchRepository.findForReport(operator, status, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public SettlementBatch getById(UUID id) {
        return batchRepository.findById(id)
            .orElseThrow(() -> new EbithexException(ErrorCode.SETTLEMENT_NOT_FOUND, "Batch introuvable: " + id));
    }

    @Transactional(readOnly = true)
    public List<SettlementEntry> getEntries(UUID batchId) {
        return entryRepository.findByBatchId(batchId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String computeBatchReference(OperatorType operator, String currency, LocalDateTime periodStart) {
        return "SET-" + operator.name().replace("_","") + "-"
            + currency + "-" + periodStart.format(REF_FMT);
    }

    private SettlementBatch buildBatch(OperatorType operator, String currency,
                                       LocalDateTime periodStart, LocalDateTime periodEnd,
                                       List<Transaction> txList) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fee   = BigDecimal.ZERO;
        for (Transaction tx : txList) {
            gross = gross.add(tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO);
            fee   = fee  .add(tx.getFeeAmount() != null ? tx.getFeeAmount() : BigDecimal.ZERO);
        }
        BigDecimal net = gross.subtract(fee);

        String ref = computeBatchReference(operator, currency, periodStart);

        return SettlementBatch.builder()
            .batchReference(ref)
            .operator(operator)
            .currency(currency)
            .periodStart(periodStart)
            .periodEnd(periodEnd)
            .transactionCount(txList.size())
            .grossAmount(gross)
            .feeAmount(fee)
            .netAmount(net)
            .status(SettlementBatchStatus.PENDING)
            .build();
    }

    private void saveEntries(SettlementBatch batch, List<Transaction> txList) {
        List<SettlementEntry> entries = new ArrayList<>(txList.size());
        for (Transaction tx : txList) {
            entries.add(SettlementEntry.builder()
                .batchId(batch.getId())
                .transactionId(tx.getId())
                .entryType(SettlementEntryType.COLLECTION)
                .amount(tx.getAmount())
                .feeAmount(tx.getFeeAmount())
                .operator(tx.getOperator())
                .currency(tx.getCurrency() != null ? tx.getCurrency().name() : "XOF")
                .build());
        }
        entryRepository.saveAll(entries);
    }
}
