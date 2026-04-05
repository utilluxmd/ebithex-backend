package com.ebithex.shared.event;

import com.ebithex.shared.domain.OperatorType;

import java.math.BigDecimal;

/**
 * Événement publié quand le float d'un opérateur passe sous son seuil d'alerte.
 * Consommé par NotificationService pour envoyer un email à l'équipe finance.
 */
public record FloatLowBalanceEvent(
    OperatorType operator,
    BigDecimal   currentBalance,
    BigDecimal   threshold
) {}