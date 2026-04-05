package com.ebithex.merchant.kyc;

import com.ebithex.merchant.domain.KycDocument;

/**
 * Contract for automated KYC document verification providers.
 *
 * Implementations: {@link SmileIdentityKycProvider} (Sub-Saharan Africa),
 *                  {@link SumsubKycProvider} (North Africa + global fallback),
 *                  {@link MockKycProvider} (local / test).
 */
public interface KycProvider {

    /** ISO 3166-1 alpha-2 country codes supported by this provider. */
    java.util.Set<String> supportedCountries();

    /**
     * Submit a document for verification.
     *
     * @param document  the persisted KycDocument (id, storageKey, type…)
     * @param countryCode merchant's ISO 3166-1 alpha-2 country code
     * @return provider-assigned job reference; persisted as {@code providerRef}
     */
    String submitVerification(KycDocument document, String countryCode);

    /**
     * Check the current status of a previously submitted verification job.
     *
     * @param providerRef  the reference returned by {@link #submitVerification}
     * @return current verification result
     */
    VerificationResult checkStatus(String providerRef);

    /** Provider identifier stored in {@code kyc_documents.provider_name}. */
    String providerName();

    record VerificationResult(
        String providerRef,
        VerificationStatus status,
        String details
    ) {}

    enum VerificationStatus {
        PENDING,   // Job queued or still processing
        ACCEPTED,  // Document verified successfully
        REJECTED,  // Document rejected by provider
        ERROR      // Unexpected provider error — treat as manual review needed
    }
}
