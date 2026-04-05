package com.ebithex.payment.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Corps optionnel de la requête POST /v1/payments/{ref}/refund.
 *
 * Si {@code amount} est null, le remboursement est total (montant restant à rembourser).
 * Si {@code amount} est inférieur au montant restant, le remboursement est partiel
 * et la transaction passe en {@code PARTIALLY_REFUNDED}.
 *
 * <p>Bean Validation : {@code @DecimalMin} et {@code @DecimalMax} s'appliquent uniquement
 * quand {@code amount} est non null (null = remboursement total, cas valide).
 */
public record RefundRequest(

    /**
     * Montant à rembourser en FCFA. Doit être > 0 et ≤ 5 000 000 FCFA.
     * Si null, remboursement total du montant restant dû.
     */
    @DecimalMin(value = "0.01", message = "Le montant de remboursement doit être positif (> 0)")
    @DecimalMax(value = "5000000.00", message = "Le montant de remboursement ne peut pas dépasser 5 000 000 FCFA")
    BigDecimal amount
) {}