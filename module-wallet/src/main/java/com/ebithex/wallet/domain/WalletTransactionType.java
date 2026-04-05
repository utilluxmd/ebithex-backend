package com.ebithex.wallet.domain;

/**
 * Types de mouvement dans le grand livre du wallet marchand.
 *
 * CREDIT_PAYMENT      : +available — paiement client réussi (netAmount)
 * DEBIT_PAYOUT        : -available +pending — décaissement initié
 * CONFIRM_PAYOUT      : -pending — décaissement confirmé (SUCCESS)
 * REFUND_PAYOUT       : -pending +available — décaissement échoué (remboursement)
 * WITHDRAWAL          : -available — retrait du marchand vers son compte externe
 * DEBIT_REFUND        : -available — remboursement client initié par le marchand
 * B2B_TRANSFER_DEBIT  : -available — virement sortant vers un autre marchand Ebithex
 * B2B_TRANSFER_CREDIT : +available — virement entrant depuis un autre marchand Ebithex
 */
public enum WalletTransactionType {
    CREDIT_PAYMENT,
    DEBIT_PAYOUT,
    CONFIRM_PAYOUT,
    REFUND_PAYOUT,
    WITHDRAWAL,
    DEBIT_REFUND,
    B2B_TRANSFER_DEBIT,
    B2B_TRANSFER_CREDIT
}