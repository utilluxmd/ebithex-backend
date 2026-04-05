package com.ebithex.payment.domain;

/** Cycle de vie d'un relevé opérateur. */
public enum OperatorStatementStatus {
    /** Importé, réconciliation pas encore lancée. */
    PENDING,
    /** Réconciliation en cours. */
    PROCESSING,
    /** Réconciliation terminée, aucune anomalie. */
    RECONCILED,
    /** Réconciliation terminée, des anomalies ont été détectées. */
    DISCREPANCY_FOUND
}