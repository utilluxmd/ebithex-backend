package com.ebithex.merchant.domain;

/**
 * Types of documents accepted for KYC verification.
 * Each merchant must upload all REQUIRED types before submitting for review.
 */
public enum KycDocumentType {

    // ── Identity documents ───────────────────────────────────────────────────
    NATIONAL_ID,           // Government-issued national identity card
    PASSPORT,              // International passport
    DRIVERS_LICENSE,       // Driver's license

    // ── Business documents ───────────────────────────────────────────────────
    BUSINESS_REGISTRATION, // Certificate of company incorporation / RCCM extract
    TAX_CERTIFICATE,       // Tax identification number certificate
    BANK_STATEMENT,        // 3-month bank statement
    PROOF_OF_ADDRESS,      // Utility bill or lease agreement ≤ 3 months old

    // ── Beneficial owner ─────────────────────────────────────────────────────
    UBO_DECLARATION,       // Ultimate beneficial owner declaration form

    // ── Other ────────────────────────────────────────────────────────────────
    OTHER;

    /** Documents that MUST be present before a KYC dossier can be submitted. */
    public static final java.util.Set<KycDocumentType> REQUIRED = java.util.Set.of(
        NATIONAL_ID,
        BUSINESS_REGISTRATION,
        PROOF_OF_ADDRESS
    );
}