package com.ebithex.payment.dto;

import java.util.UUID;

/**
 * Résultat retourné après import + réconciliation d'un relevé opérateur.
 */
public record StatementImportResult(
    UUID   statementId,
    int    totalLines,
    int    matchedLines,
    int    discrepancyLines,
    String status
) {}