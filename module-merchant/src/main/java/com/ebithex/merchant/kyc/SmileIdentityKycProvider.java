package com.ebithex.merchant.kyc;

import com.ebithex.merchant.domain.KycDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Smile Identity KYC provider — covers ~35 Sub-Saharan African countries.
 *
 * This is a stub implementation. Wire the actual Smile Identity REST API
 * (https://docs.smileidentity.com) before going to production.
 *
 * Active when {@code ebithex.kyc.provider.smile.enabled=true}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ebithex.kyc.provider.smile.enabled", havingValue = "true")
public class SmileIdentityKycProvider implements KycProvider {

    /** Sub-Saharan African countries supported by Smile Identity. */
    private static final Set<String> SUPPORTED = Set.of(
        "NG", // Nigeria
        "GH", // Ghana
        "KE", // Kenya
        "SN", // Senegal
        "CI", // Côte d'Ivoire
        "CM", // Cameroon
        "TZ", // Tanzania
        "UG", // Uganda
        "ZA", // South Africa
        "ET", // Ethiopia
        "BJ", // Benin
        "BF", // Burkina Faso
        "ML", // Mali
        "NE", // Niger
        "TG", // Togo
        "GN", // Guinea
        "SL", // Sierra Leone
        "LR", // Liberia
        "MR", // Mauritania
        "GM", // Gambia
        "GW", // Guinea-Bissau
        "CV", // Cape Verde
        "RW", // Rwanda
        "BI", // Burundi
        "CD", // DR Congo
        "CG", // Republic of Congo
        "GA", // Gabon
        "GQ", // Equatorial Guinea
        "CF", // Central African Republic
        "TD", // Chad
        "ZM", // Zambia
        "ZW", // Zimbabwe
        "MW", // Malawi
        "MZ", // Mozambique
        "AO"  // Angola
    );

    @Value("${ebithex.kyc.provider.smile.partner-id:}")
    private String partnerId;

    @Value("${ebithex.kyc.provider.smile.api-key:}")
    private String apiKey;

    @Value("${ebithex.kyc.provider.smile.base-url:https://testapi.smileidentity.com/v1}")
    private String baseUrl;

    @Override
    public Set<String> supportedCountries() {
        return SUPPORTED;
    }

    @Override
    public String submitVerification(KycDocument document, String countryCode) {
        // TODO: implement actual Smile Identity Document Verification API call
        // POST {baseUrl}/upload — multipart with image + metadata
        log.info("[SmileIdentity] Submit verification for doc={} merchant={} country={}",
            document.getId(), document.getMerchantId(), countryCode);
        throw new UnsupportedOperationException("SmileIdentity integration not yet implemented — configure ebithex.kyc.provider.smile.*");
    }

    @Override
    public VerificationResult checkStatus(String providerRef) {
        // TODO: implement GET {baseUrl}/job_status
        throw new UnsupportedOperationException("SmileIdentity integration not yet implemented");
    }

    @Override
    public String providerName() {
        return "smile_identity";
    }
}
