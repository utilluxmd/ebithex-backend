package com.ebithex.payment.domain;

/**
 * Types d'écart détectés lors de la réconciliation opérateur.
 */
public enum DiscrepancyType {
    /** Ligne concordante — aucune anomalie. */
    MATCHED,
    /** L'opérateur liste une transaction absente de notre système. */
    MISSING_IN_EBITHEX,
    /** Notre système a une transaction absente du relevé opérateur. */
    MISSING_IN_OPERATOR,
    /** Les montants diffèrent entre notre système et le relevé. */
    AMOUNT_MISMATCH,
    /** Les statuts diffèrent (ex. opérateur SUCCESS mais Ebithex FAILED). */
    STATUS_MISMATCH
}