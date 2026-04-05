package com.ebithex.dispute.application;

import com.ebithex.dispute.domain.Dispute;
import com.ebithex.dispute.domain.DisputeStatus;
import com.ebithex.dispute.dto.DisputeRequest;
import com.ebithex.dispute.dto.DisputeResolutionRequest;
import com.ebithex.dispute.infrastructure.DisputeRepository;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Gestion du cycle de vie des litiges marchands.
 *
 * Un litige peut être ouvert par un marchand sur n'importe quelle transaction SUCCESS.
 * Le back-office le prend en charge (UNDER_REVIEW) puis le résout en faveur du
 * marchand (RESOLVED_MERCHANT) ou du client (RESOLVED_CUSTOMER).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DisputeService {

    private static final EnumSet<DisputeStatus> RESOLVABLE =
        EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    private final DisputeRepository      disputeRepository;
    private final TransactionRepository  transactionRepository;

    /** Ouvre un nouveau litige (appelé par le marchand). */
    @Transactional
    public Dispute openDispute(DisputeRequest req, UUID merchantId) {
        // Vérifie que la transaction existe et appartient au marchand
        Transaction tx = transactionRepository.findByEbithexReference(req.ebithexReference())
            .filter(t -> t.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction introuvable: " + req.ebithexReference()));

        // Vérifie qu'aucun litige ouvert n'existe déjà pour cette référence
        disputeRepository.findByEbithexReferenceAndMerchantId(req.ebithexReference(), merchantId)
            .ifPresent(existing -> {
                throw new EbithexException(ErrorCode.DISPUTE_ALREADY_EXISTS,
                    "Un litige existe déjà pour cette transaction: " + existing.getId());
            });

        Dispute dispute = Dispute.builder()
            .ebithexReference(req.ebithexReference())
            .merchantId(merchantId)
            .transactionId(tx.getId())
            .reason(req.reason())
            .description(req.description())
            .amount(tx.getAmount())
            .currency(tx.getCurrency() != null ? tx.getCurrency().name() : null)
            .status(DisputeStatus.OPEN)
            .build();

        Dispute saved = disputeRepository.save(dispute);
        log.info("Litige ouvert: {} | marchand={} | transaction={}",
            saved.getId(), merchantId, req.ebithexReference());
        return saved;
    }

    /** Liste les litiges d'un marchand. */
    @Transactional(readOnly = true)
    public Page<Dispute> listForMerchant(UUID merchantId, Pageable pageable) {
        return disputeRepository.findByMerchantId(merchantId, pageable);
    }

    /** Détail d'un litige (visible par le marchand propriétaire). */
    @Transactional(readOnly = true)
    public Dispute getForMerchant(UUID disputeId, UUID merchantId) {
        return disputeRepository.findById(disputeId)
            .filter(d -> d.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new EbithexException(ErrorCode.DISPUTE_NOT_FOUND, "Litige introuvable: " + disputeId));
    }

    /** Récupère un litige par ID (back-office). */
    @Transactional(readOnly = true)
    public Dispute getById(UUID disputeId) {
        return disputeRepository.findById(disputeId)
            .orElseThrow(() -> new EbithexException(ErrorCode.DISPUTE_NOT_FOUND, "Litige introuvable: " + disputeId));
    }

    /** Annulation par le marchand (OPEN uniquement). */
    @Transactional
    public Dispute cancelByMerchant(UUID disputeId, UUID merchantId) {
        Dispute d = getForMerchant(disputeId, merchantId);
        if (d.getStatus() != DisputeStatus.OPEN) {
            throw new EbithexException(ErrorCode.DISPUTE_CANNOT_CANCEL,
                "Seul un litige OPEN peut être annulé. Statut actuel: " + d.getStatus());
        }
        d.setStatus(DisputeStatus.CANCELLED);
        return disputeRepository.save(d);
    }

    // ── Back-office ──────────────────────────────────────────────────────────

    /** Prend en charge un litige (OPEN → UNDER_REVIEW). */
    @Transactional
    public Dispute startReview(UUID disputeId, String reviewerEmail) {
        Dispute d = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new EbithexException(ErrorCode.DISPUTE_NOT_FOUND, "Litige introuvable: " + disputeId));
        if (d.getStatus() != DisputeStatus.OPEN) {
            throw new EbithexException(ErrorCode.DISPUTE_INVALID_TRANSITION,
                "Transition invalide depuis: " + d.getStatus());
        }
        d.setStatus(DisputeStatus.UNDER_REVIEW);
        d.setResolvedBy(reviewerEmail);
        log.info("Litige {} pris en charge par {}", disputeId, reviewerEmail);
        return disputeRepository.save(d);
    }

    /** Résolution back-office. */
    @Transactional
    public Dispute resolve(UUID disputeId, DisputeResolutionRequest req, String resolvedBy) {
        Dispute d = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new EbithexException(ErrorCode.DISPUTE_NOT_FOUND, "Litige introuvable: " + disputeId));

        if (!RESOLVABLE.contains(d.getStatus())) {
            throw new EbithexException(ErrorCode.DISPUTE_INVALID_TRANSITION,
                "Impossible de résoudre un litige en statut: " + d.getStatus());
        }
        if (req.status() != DisputeStatus.RESOLVED_MERCHANT &&
            req.status() != DisputeStatus.RESOLVED_CUSTOMER) {
            throw new EbithexException(ErrorCode.DISPUTE_INVALID_STATUS,
                "Statut de résolution invalide: " + req.status());
        }
        d.setStatus(req.status());
        d.setResolutionNotes(req.resolutionNotes());
        d.setResolvedBy(resolvedBy);
        d.setResolvedAt(LocalDateTime.now());
        log.info("Litige {} résolu {} par {}", disputeId, req.status(), resolvedBy);
        return disputeRepository.save(d);
    }

    @Transactional(readOnly = true)
    public Page<Dispute> listForBackOffice(
            DisputeStatus status, UUID merchantId,
            LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return disputeRepository.findForBackOffice(status, merchantId, from, to, pageable);
    }
}
