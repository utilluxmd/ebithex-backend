package com.ebithex.wallet.domain;

/**
 * Cycle de vie d'une demande de retrait marchand.
 *
 * PENDING  → en attente de validation par l'équipe finance
 * APPROVED → validée, solde débité, transfert bancaire en cours
 * REJECTED → refusée (motif enregistré dans rejection_reason)
 * EXECUTED → transfert finalisé (confirmation manuelle ou bancaire)
 */
public enum WithdrawalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXECUTED
}