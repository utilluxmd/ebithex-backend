package com.ebithex.merchant.kyc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Routes to the correct {@link KycProvider} for a merchant's country.
 *
 * Resolution order:
 *  1. First provider whose {@code supportedCountries()} contains the country code
 *  2. Provider named "sumsub" as global fallback
 *  3. MockKycProvider if nothing matches (safe for local/test)
 */
@Slf4j
@Component
public class KycProviderRegistry {

    private final List<KycProvider> providers;

    public KycProviderRegistry(List<KycProvider> providers) {
        this.providers = providers;
    }

    public KycProvider forCountry(String isoCountryCode) {
        String code = isoCountryCode == null ? "" : isoCountryCode.toUpperCase();

        return providers.stream()
            .filter(p -> p.supportedCountries().contains(code))
            .findFirst()
            .or(() -> providers.stream()
                .filter(p -> "sumsub".equals(p.providerName()))
                .findFirst())
            .orElseGet(() -> {
                log.warn("No KYC provider for country '{}', falling back to mock", code);
                return providers.stream()
                    .filter(p -> "mock".equals(p.providerName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No KYC provider available"));
            });
    }
}
