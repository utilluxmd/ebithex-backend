package com.ebithex.merchant.kyc;

import com.ebithex.merchant.domain.KycDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Sumsub KYC provider — North Africa + global fallback.
 *
 * This is a stub implementation. Wire the actual Sumsub REST API
 * (https://developers.sumsub.com) before going to production.
 *
 * Active when {@code ebithex.kyc.provider.sumsub.enabled=true}.
 * Acts as global fallback in {@link KycProviderRegistry} when providerName()=="sumsub".
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ebithex.kyc.provider.sumsub.enabled", havingValue = "true")
public class SumsubKycProvider implements KycProvider {

    /** North African countries + any country not covered by Smile Identity. */
    private static final Set<String> SUPPORTED = Set.of(
        "MA", // Morocco
        "DZ", // Algeria
        "TN", // Tunisia
        "LY", // Libya
        "EG", // Egypt
        "SD", // Sudan
        "SS", // South Sudan
        "ER", // Eritrea
        "DJ", // Djibouti
        "SO", // Somalia
        "KM", // Comoros
        "MG", // Madagascar
        "MU", // Mauritius
        "SC", // Seychelles
        "RE", // Reunion
        "YT", // Mayotte
        "ST", // São Tomé and Príncipe
        "NA", // Namibia
        "BW", // Botswana
        "LS", // Lesotho
        "SZ", // Eswatini
        "IO"  // British Indian Ocean Territory
    );

    @Value("${ebithex.kyc.provider.sumsub.app-token:}")
    private String appToken;

    @Value("${ebithex.kyc.provider.sumsub.secret-key:}")
    private String secretKey;

    @Value("${ebithex.kyc.provider.sumsub.base-url:https://api.sumsub.com}")
    private String baseUrl;

    @Override
    public Set<String> supportedCountries() {
        return SUPPORTED;
    }

    @Override
    public String submitVerification(KycDocument document, String countryCode) {
        // TODO: implement Sumsub applicant creation + document upload
        // POST {baseUrl}/resources/applicants + POST {baseUrl}/resources/applicants/{id}/info/idDoc
        log.info("[Sumsub] Submit verification for doc={} merchant={} country={}",
            document.getId(), document.getMerchantId(), countryCode);
        throw new UnsupportedOperationException("Sumsub integration not yet implemented — configure ebithex.kyc.provider.sumsub.*");
    }

    @Override
    public VerificationResult checkStatus(String providerRef) {
        // TODO: implement GET {baseUrl}/resources/applicants/{applicantId}/status
        throw new UnsupportedOperationException("Sumsub integration not yet implemented");
    }

    @Override
    public String providerName() {
        return "sumsub";
    }
}