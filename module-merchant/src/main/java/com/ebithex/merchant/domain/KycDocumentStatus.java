package com.ebithex.merchant.domain;

public enum KycDocumentStatus {
    UPLOADED,    // Just uploaded, awaiting provider check
    PROCESSING,  // Provider verification in progress
    ACCEPTED,    // Document validated by provider / back-office
    REJECTED,    // Document rejected (blurry, expired, wrong type…)
    EXPIRED      // Document past its validity date
}