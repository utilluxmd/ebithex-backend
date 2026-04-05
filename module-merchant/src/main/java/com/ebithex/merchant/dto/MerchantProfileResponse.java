package com.ebithex.merchant.dto;

import com.ebithex.merchant.domain.KycStatus;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.shared.domain.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Réponse profil marchand — exclut hashedSecret et apiKeyHash.
 */
public record MerchantProfileResponse(
    UUID          id,
    String        businessName,
    String        email,
    String        country,
    boolean       active,
    KycStatus     kycStatus,
    boolean       kycVerified,
    String        kycRejectionReason,
    String        webhookUrl,
    BigDecimal    customFeeRate,
    Currency      defaultCurrency,
    LocalDateTime createdAt
) {
    public static MerchantProfileResponse from(Merchant m) {
        return new MerchantProfileResponse(
            m.getId(), m.getBusinessName(), m.getEmail(), m.getCountry(),
            m.isActive(), m.getKycStatus(), m.isKycVerified(),
            m.getKycRejectionReason(), m.getWebhookUrl(),
            m.getCustomFeeRate(), m.getDefaultCurrency(), m.getCreatedAt()
        );
    }
}