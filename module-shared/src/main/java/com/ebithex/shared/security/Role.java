package com.ebithex.shared.security;

public enum Role {
    // ── Merchant roles (auth by API Key or JWT) ──
    MERCHANT,
    MERCHANT_KYC_VERIFIED,
    AGENT,

    // ── Internal back-office roles (auth by JWT) ──
    SUPPORT,
    FINANCE,
    RECONCILIATION,
    COMPLIANCE,
    COUNTRY_ADMIN,
    ADMIN,
    SUPER_ADMIN
}
