package com.ebithex.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Résultat d'un virement B2B inter-marchands.
 *
 * @param ebithexReference   Référence Ebithex unique du transfert
 * @param senderMerchantId   UUID de l'expéditeur
 * @param receiverMerchantId UUID du destinataire
 * @param amount             Montant transféré
 * @param currency           Devise du transfert
 * @param description        Description du virement
 * @param createdAt          Horodatage du transfert
 */
public record B2bTransferResponse(
    String ebithexReference,
    UUID senderMerchantId,
    UUID receiverMerchantId,
    BigDecimal amount,
    String currency,
    String description,
    Instant createdAt
) {}