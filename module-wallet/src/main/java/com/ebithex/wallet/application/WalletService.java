package com.ebithex.wallet.application;

import com.ebithex.shared.domain.Currency;
import com.ebithex.wallet.domain.MerchantWithdrawal;
import com.ebithex.wallet.domain.Wallet;
import com.ebithex.wallet.domain.WalletTransaction;
import com.ebithex.wallet.domain.WalletTransactionType;
import com.ebithex.wallet.domain.WithdrawalStatus;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import com.ebithex.wallet.dto.WalletResponse;
import com.ebithex.wallet.dto.WalletTransactionResponse;
import com.ebithex.wallet.dto.WithdrawalResponse;
import com.ebithex.wallet.dto.WithdrawalSummaryResponse;
import com.ebithex.wallet.infrastructure.MerchantWithdrawalRepository;
import com.ebithex.wallet.infrastructure.WalletRepository;
import com.ebithex.wallet.infrastructure.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository              walletRepository;
    private final WalletTransactionRepository   walletTransactionRepository;
    private final MerchantWithdrawalRepository  withdrawalRepository;

    // ─── Lecture ──────────────────────────────────────────────────────────────

    /**
     * Retourne tous les wallets d'un marchand (toutes devises).
     */
    @Transactional(readOnly = true)
    public List<WalletResponse> getBalances(UUID merchantId) {
        return walletRepository.findByMerchantId(merchantId)
            .stream()
            .map(WalletResponse::from)
            .toList();
    }

    /**
     * Retourne le wallet d'un marchand dans une devise spécifique.
     * Crée le wallet si inexistant (avec solde 0).
     */
    @Transactional(readOnly = true)
    public WalletResponse getBalance(UUID merchantId, Currency currency) {
        Wallet wallet = getOrCreate(merchantId, currency);
        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public Page<WalletTransactionResponse> getTransactions(UUID merchantId, Pageable pageable) {
        return walletTransactionRepository
            .findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
            .map(WalletTransactionResponse::from);
    }

    // ─── Mouvements ──────────────────────────────────────────────────────────

    /**
     * Crédite le wallet du marchand quand un paiement client réussit.
     * Montant crédité = netAmount (montant brut - frais Ebithex).
     * Le wallet est identifié par (merchantId, currency).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void creditPayment(UUID merchantId, BigDecimal netAmount, String ebithexReference, Currency currency) {
        if (alreadyProcessed(ebithexReference, WalletTransactionType.CREDIT_PAYMENT)) return;
        try {
            Wallet wallet = getOrCreateForUpdate(merchantId, currency);
            wallet.setAvailableBalance(wallet.getAvailableBalance().add(netAmount));
            walletRepository.save(wallet);
            recordTransaction(wallet, WalletTransactionType.CREDIT_PAYMENT,
                netAmount, wallet.getAvailableBalance(), ebithexReference,
                "Crédit paiement reçu");
            log.info("Wallet crédit: merchant={} +{} {} ref={}", merchantId, netAmount, currency, ebithexReference);
        } catch (DataIntegrityViolationException e) {
            log.info("Wallet crédit ignoré (déjà traité): ref={}", ebithexReference);
        }
    }

    /**
     * Débite le wallet quand un décaissement est initié.
     * Déplace le montant de available vers pending (fonds bloqués).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void debitPayout(UUID merchantId, BigDecimal amount, String ebithexReference, Currency currency) {
        if (alreadyProcessed(ebithexReference, WalletTransactionType.DEBIT_PAYOUT)) return;
        try {
            Wallet wallet = getOrCreateForUpdate(merchantId, currency);
            wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
            wallet.setPendingBalance(wallet.getPendingBalance().add(amount));
            walletRepository.save(wallet);
            recordTransaction(wallet, WalletTransactionType.DEBIT_PAYOUT,
                amount, wallet.getAvailableBalance(), ebithexReference,
                "Débit décaissement initié");
            log.info("Wallet débit payout: merchant={} -{} {} ref={}", merchantId, amount, currency, ebithexReference);
        } catch (DataIntegrityViolationException e) {
            log.info("Wallet débit payout ignoré (déjà traité): ref={}", ebithexReference);
        }
    }

    /**
     * Confirme un décaissement réussi.
     * Libère le montant du pending (consommé définitivement).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirmPayout(UUID merchantId, BigDecimal amount, String ebithexReference, Currency currency) {
        if (alreadyProcessed(ebithexReference, WalletTransactionType.CONFIRM_PAYOUT)) return;
        try {
            Wallet wallet = getOrCreateForUpdate(merchantId, currency);
            wallet.setPendingBalance(wallet.getPendingBalance().subtract(amount));
            walletRepository.save(wallet);
            recordTransaction(wallet, WalletTransactionType.CONFIRM_PAYOUT,
                amount, wallet.getAvailableBalance(), ebithexReference,
                "Décaissement confirmé");
            log.info("Wallet confirm payout: merchant={} pending-{} {} ref={}", merchantId, amount, currency, ebithexReference);
        } catch (DataIntegrityViolationException e) {
            log.info("Wallet confirm payout ignoré (déjà traité): ref={}", ebithexReference);
        }
    }

    /**
     * Rembourse un décaissement échoué.
     * Remet le montant de pending vers available.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundPayout(UUID merchantId, BigDecimal amount, String ebithexReference, Currency currency) {
        if (alreadyProcessed(ebithexReference, WalletTransactionType.REFUND_PAYOUT)) return;
        try {
            Wallet wallet = getOrCreateForUpdate(merchantId, currency);
            wallet.setPendingBalance(wallet.getPendingBalance().subtract(amount));
            wallet.setAvailableBalance(wallet.getAvailableBalance().add(amount));
            walletRepository.save(wallet);
            recordTransaction(wallet, WalletTransactionType.REFUND_PAYOUT,
                amount, wallet.getAvailableBalance(), ebithexReference,
                "Remboursement décaissement échoué");
            log.info("Wallet refund payout: merchant={} +{} {} ref={}", merchantId, amount, currency, ebithexReference);
        } catch (DataIntegrityViolationException e) {
            log.info("Wallet refund payout ignoré (déjà traité): ref={}", ebithexReference);
        }
    }

    /**
     * Débite le wallet lors d'un remboursement marchand vers un client.
     *
     * @throws EbithexException INSUFFICIENT_BALANCE si le solde disponible est insuffisant
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void debitRefund(UUID merchantId, BigDecimal amount, String ebithexReference, Currency currency) {
        if (alreadyProcessed(ebithexReference, WalletTransactionType.DEBIT_REFUND)) return;
        try {
            Wallet wallet = getOrCreateForUpdate(merchantId, currency);
            if (wallet.getAvailableBalance().compareTo(amount) < 0) {
                throw new EbithexException(ErrorCode.INSUFFICIENT_BALANCE,
                    "Solde insuffisant pour rembourser: " + wallet.getAvailableBalance()
                    + " < " + amount + " " + currency.name());
            }
            wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
            walletRepository.save(wallet);
            recordTransaction(wallet, WalletTransactionType.DEBIT_REFUND,
                amount, wallet.getAvailableBalance(), ebithexReference,
                "Remboursement client initié par le marchand");
            log.info("Wallet débit refund: merchant={} -{} {} ref={}", merchantId, amount, currency, ebithexReference);
        } catch (EbithexException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            log.info("Wallet débit refund ignoré (déjà traité): ref={}", ebithexReference);
        }
    }

    // ─── Transfert B2B inter-marchands ───────────────────────────────────────

    /**
     * Transfère des fonds du wallet d'un marchand vers celui d'un autre marchand Ebithex.
     *
     * <p>L'opération est atomique : le débit expéditeur et le crédit destinataire
     * sont effectués dans la même transaction Spring (pas de REQUIRES_NEW ici).
     * Le verrou pessimiste ORDER BY merchantId garantit l'absence de deadlock
     * quand deux marchands s'envoient des fonds simultanément.
     *
     * <p>Idempotent via {@code ebithexReference} : un second appel avec la même référence
     * retourne directement les soldes courants sans re-débiter.
     *
     * @param senderMerchantId   UUID du marchand expéditeur
     * @param receiverMerchantId UUID du marchand destinataire
     * @param amount             Montant à transférer (strictement positif)
     * @param currency           Devise du transfert
     * @param ebithexReference   Référence unique Ebithex (pour idempotence)
     * @param description        Description libre du transfert
     * @throws EbithexException INSUFFICIENT_BALANCE si le solde expéditeur est insuffisant
     * @throws EbithexException INVALID_TRANSFER si expéditeur == destinataire
     */
    @Transactional
    public void transfer(UUID senderMerchantId, UUID receiverMerchantId,
                         BigDecimal amount, Currency currency,
                         String ebithexReference, String description) {

        if (senderMerchantId.equals(receiverMerchantId)) {
            throw new EbithexException(ErrorCode.INVALID_TRANSFER,
                "L'expéditeur et le destinataire ne peuvent pas être le même marchand");
        }

        // Idempotence : si le débit a déjà été enregistré, rien à faire
        if (alreadyProcessed(ebithexReference, WalletTransactionType.B2B_TRANSFER_DEBIT)) {
            log.info("Transfert B2B idempotent ignoré: ref={}", ebithexReference);
            return;
        }

        // Verrou ordre déterministe pour éviter les deadlocks A→B / B→A
        UUID firstLock  = senderMerchantId.compareTo(receiverMerchantId) < 0 ? senderMerchantId  : receiverMerchantId;
        UUID secondLock = senderMerchantId.compareTo(receiverMerchantId) < 0 ? receiverMerchantId : senderMerchantId;

        Wallet first  = getOrCreateForUpdate(firstLock,  currency);
        Wallet second = getOrCreateForUpdate(secondLock, currency);

        Wallet senderWallet   = first.getMerchantId().equals(senderMerchantId)   ? first  : second;
        Wallet receiverWallet = first.getMerchantId().equals(receiverMerchantId) ? first  : second;

        if (senderWallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new EbithexException(ErrorCode.INSUFFICIENT_BALANCE,
                "Solde insuffisant pour le transfert: " + senderWallet.getAvailableBalance()
                + " < " + amount + " " + currency.name());
        }

        senderWallet.setAvailableBalance(senderWallet.getAvailableBalance().subtract(amount));
        receiverWallet.setAvailableBalance(receiverWallet.getAvailableBalance().add(amount));

        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        String desc = description != null && !description.isBlank() ? description : "Virement B2B";
        recordTransaction(senderWallet,   WalletTransactionType.B2B_TRANSFER_DEBIT,
            amount, senderWallet.getAvailableBalance(),   ebithexReference,
            desc + " → " + receiverMerchantId);
        recordTransaction(receiverWallet, WalletTransactionType.B2B_TRANSFER_CREDIT,
            amount, receiverWallet.getAvailableBalance(), ebithexReference,
            desc + " ← " + senderMerchantId);

        log.info("Transfert B2B: {} → {} montant={} {} ref={}",
            senderMerchantId, receiverMerchantId, amount, currency, ebithexReference);
    }

    // ─── Retrait — workflow d'approbation ────────────────────────────────────

    /**
     * Soumet une demande de retrait dans une devise spécifique.
     *
     * @throws EbithexException INSUFFICIENT_BALANCE si le solde disponible est insuffisant
     */
    @Transactional
    public WithdrawalResponse requestWithdrawal(UUID merchantId, BigDecimal amount,
                                                 String reference, String description,
                                                 Currency currency) {
        return withdrawalRepository.findByReference(reference)
            .map(existing -> new WithdrawalResponse(
                existing.getId(), existing.getReference(), merchantId,
                existing.getAmount(), existing.getCurrency().name(),
                existing.getStatus().name(), existing.getCreatedAt()))
            .orElseGet(() -> {
                Wallet wallet = getOrCreate(merchantId, currency);
                if (wallet.getAvailableBalance().compareTo(amount) < 0) {
                    throw new EbithexException(ErrorCode.INSUFFICIENT_BALANCE,
                        "Solde disponible insuffisant: " + wallet.getAvailableBalance()
                        + " < " + amount + " " + currency.name());
                }
                MerchantWithdrawal withdrawal = withdrawalRepository.save(
                    MerchantWithdrawal.builder()
                        .merchantId(merchantId)
                        .amount(amount)
                        .currency(currency)
                        .reference(reference)
                        .description(description != null ? description : "Demande de retrait")
                        .status(WithdrawalStatus.PENDING)
                        .build()
                );
                log.info("Retrait soumis (en attente): merchant={} montant={} {} ref={}",
                    merchantId, amount, currency, reference);
                return new WithdrawalResponse(
                    withdrawal.getId(), withdrawal.getReference(), merchantId,
                    amount, currency.name(), WithdrawalStatus.PENDING.name(), withdrawal.getCreatedAt());
            });
    }

    /**
     * Approuve une demande de retrait : débite le wallet et passe le statut à APPROVED.
     *
     * @throws EbithexException WITHDRAWAL_NOT_FOUND, WITHDRAWAL_ALREADY_PROCESSED, INSUFFICIENT_BALANCE
     */
    @Transactional
    public WithdrawalSummaryResponse approveWithdrawal(UUID withdrawalId, UUID reviewedBy) {
        MerchantWithdrawal withdrawal = findWithdrawalForReview(withdrawalId);

        Wallet wallet = getOrCreateForUpdate(withdrawal.getMerchantId(), withdrawal.getCurrency());
        if (wallet.getAvailableBalance().compareTo(withdrawal.getAmount()) < 0) {
            throw new EbithexException(ErrorCode.INSUFFICIENT_BALANCE,
                "Solde disponible insuffisant pour approuver: " + wallet.getAvailableBalance());
        }
        wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(withdrawal.getAmount()));
        walletRepository.save(wallet);
        recordTransaction(wallet, WalletTransactionType.WITHDRAWAL,
            withdrawal.getAmount(), wallet.getAvailableBalance(),
            withdrawal.getReference(), "Retrait approuvé — " + withdrawal.getReference());

        withdrawal.setStatus(WithdrawalStatus.APPROVED);
        withdrawal.setReviewedBy(reviewedBy);
        withdrawal.setReviewedAt(LocalDateTime.now());
        withdrawalRepository.save(withdrawal);

        log.info("Retrait approuvé: id={} merchant={} montant={} {} by={}",
            withdrawalId, withdrawal.getMerchantId(), withdrawal.getAmount(),
            withdrawal.getCurrency(), reviewedBy);
        return WithdrawalSummaryResponse.from(withdrawal);
    }

    @Transactional
    public WithdrawalSummaryResponse rejectWithdrawal(UUID withdrawalId, UUID reviewedBy, String reason) {
        MerchantWithdrawal withdrawal = findWithdrawalForReview(withdrawalId);

        withdrawal.setStatus(WithdrawalStatus.REJECTED);
        withdrawal.setReviewedBy(reviewedBy);
        withdrawal.setReviewedAt(LocalDateTime.now());
        withdrawal.setRejectionReason(reason);
        withdrawalRepository.save(withdrawal);

        log.info("Retrait rejeté: id={} merchant={} raison='{}' by={}",
            withdrawalId, withdrawal.getMerchantId(), reason, reviewedBy);
        return WithdrawalSummaryResponse.from(withdrawal);
    }

    @Transactional
    public WithdrawalSummaryResponse markWithdrawalExecuted(UUID withdrawalId, UUID reviewedBy) {
        MerchantWithdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
            .orElseThrow(() -> new EbithexException(ErrorCode.WITHDRAWAL_NOT_FOUND,
                "Demande de retrait introuvable: " + withdrawalId));
        if (withdrawal.getStatus() != WithdrawalStatus.APPROVED) {
            throw new EbithexException(ErrorCode.WITHDRAWAL_INVALID_STATE,
                "Seules les demandes APPROVED peuvent être marquées EXECUTED. Statut actuel: " + withdrawal.getStatus());
        }
        withdrawal.setStatus(WithdrawalStatus.EXECUTED);
        withdrawal.setReviewedBy(reviewedBy);
        withdrawal.setReviewedAt(LocalDateTime.now());
        withdrawalRepository.save(withdrawal);
        log.info("Retrait exécuté: id={} merchant={}", withdrawalId, withdrawal.getMerchantId());
        return WithdrawalSummaryResponse.from(withdrawal);
    }

    @Transactional(readOnly = true)
    public Page<WithdrawalSummaryResponse> listWithdrawals(WithdrawalStatus status, Pageable pageable) {
        if (status != null) {
            return withdrawalRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                .map(WithdrawalSummaryResponse::from);
        }
        return withdrawalRepository.findAllByOrderByCreatedAtDesc(pageable)
            .map(WithdrawalSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<WithdrawalSummaryResponse> listMerchantWithdrawals(UUID merchantId, Pageable pageable) {
        return withdrawalRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
            .map(WithdrawalSummaryResponse::from);
    }

    private MerchantWithdrawal findWithdrawalForReview(UUID withdrawalId) {
        MerchantWithdrawal w = withdrawalRepository.findById(withdrawalId)
            .orElseThrow(() -> new EbithexException(ErrorCode.WITHDRAWAL_NOT_FOUND,
                "Demande de retrait introuvable: " + withdrawalId));
        if (w.getStatus() != WithdrawalStatus.PENDING) {
            throw new EbithexException(ErrorCode.WITHDRAWAL_ALREADY_PROCESSED,
                "Cette demande a déjà été traitée. Statut actuel: " + w.getStatus());
        }
        return w;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Wallet getOrCreate(UUID merchantId, Currency currency) {
        return walletRepository.findByMerchantIdAndCurrency(merchantId, currency)
            .orElseGet(() -> walletRepository.save(buildNewWallet(merchantId, currency)));
    }

    private Wallet getOrCreateForUpdate(UUID merchantId, Currency currency) {
        return walletRepository.findByMerchantIdAndCurrencyForUpdate(merchantId, currency)
            .orElseGet(() -> walletRepository.save(buildNewWallet(merchantId, currency)));
    }

    private Wallet buildNewWallet(UUID merchantId, Currency currency) {
        BigDecimal initialBalance = SandboxContextHolder.isSandbox()
            ? new BigDecimal("1000000") // 1 000 000 XOF pré-financé en sandbox
            : BigDecimal.ZERO;
        return Wallet.builder()
            .merchantId(merchantId)
            .currency(currency)
            .availableBalance(initialBalance)
            .build();
    }

    private boolean alreadyProcessed(String ebithexReference, WalletTransactionType type) {
        return walletTransactionRepository.existsByEbithexReferenceAndType(ebithexReference, type);
    }

    private void recordTransaction(Wallet wallet, WalletTransactionType type,
                                   BigDecimal amount, BigDecimal balanceAfter,
                                   String ebithexReference, String description) {
        walletTransactionRepository.save(WalletTransaction.builder()
            .walletId(wallet.getId())
            .merchantId(wallet.getMerchantId())
            .type(type)
            .amount(amount)
            .balanceAfter(balanceAfter)
            .ebithexReference(ebithexReference)
            .description(description)
            .build());
    }
}