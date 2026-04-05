package com.ebithex.shared.event;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Événement domaine publié à chaque changement de statut de décaissement.
 *
 * L'événement est auto-portant (contient les montants) pour éviter
 * que les consommateurs aient à requêter la DB.
 *
 * Consommateurs :
 *  - WalletService      : débite/rembourse le marchand selon l'état
 *  - WebhookService     : notifie le marchand (via outbox)
 *  - NotificationService: SMS/email (futur)
 */
public record PayoutStatusChangedEvent(
    UUID              payoutId,
    String            ebithexReference,
    String            merchantReference,
    UUID              merchantId,
    TransactionStatus newStatus,
    TransactionStatus previousStatus,
    BigDecimal        amount,
    BigDecimal        feeAmount,
    BigDecimal        netAmount,
    Currency          currency,
    OperatorType      operator
) {}