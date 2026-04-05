package com.ebithex.merchant.dto;

import com.ebithex.merchant.domain.KycStatus;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.shared.domain.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO back-office — exclut intentionnellement hashedSecret et apiKeyHash.
 */
public record MerchantAdminResponse(
    UUID          id,
    String        businessName,
    String        email,
    String        country,
    boolean       active,
    boolean       kycVerified,
    KycStatus     kycStatus,
    String        kycRejectionReason,
    LocalDateTime kycSubmittedAt,
    LocalDateTime kycReviewedAt,
    String        webhookUrl,
    BigDecimal    customFeeRate,
    Currency      defaultCurrency,
    boolean       testMode,
    BigDecimal    dailyPaymentLimit,
    BigDecimal    monthlyPaymentLimit,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static MerchantAdminResponse from(Merchant m) {
        return new MerchantAdminResponse(
            m.getId(), m.getBusinessName(), m.getEmail(), m.getCountry(),
            m.isActive(), m.isKycVerified(), m.getKycStatus(),
            m.getKycRejectionReason(), m.getKycSubmittedAt(), m.getKycReviewedAt(),
            m.getWebhookUrl(), m.getCustomFeeRate(), m.getDefaultCurrency(),
            m.isTestMode(), m.getDailyPaymentLimit(), m.getMonthlyPaymentLimit(),
            m.getCreatedAt(), m.getUpdatedAt()
        );
    }
}