package com.ebithex.merchant.kyc;

import com.ebithex.merchant.domain.KycDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Mock provider for local development and integration tests.
 * Always returns ACCEPTED immediately — no real HTTP calls.
 *
 * Active when {@code ebithex.kyc.provider.mock.enabled=true} (set automatically
 * by the local and test profiles).
 */
@Component
@ConditionalOnProperty(name = "ebithex.kyc.provider.mock.enabled", havingValue = "true")
public class MockKycProvider implements KycProvider {

    @Override
    public Set<String> supportedCountries() {
        // Returns empty — only used as last-resort fallback by the registry
        return Set.of();
    }

    @Override
    public String submitVerification(KycDocument document, String countryCode) {
        return "mock-" + UUID.randomUUID();
    }

    @Override
    public VerificationResult checkStatus(String providerRef) {
        return new VerificationResult(providerRef, VerificationStatus.ACCEPTED, "Mock auto-accept");
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
