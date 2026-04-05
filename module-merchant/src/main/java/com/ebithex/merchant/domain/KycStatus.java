package com.ebithex.merchant.domain;

public enum KycStatus {
    NONE,      // État initial — aucun KYC soumis
    PENDING,   // Soumis, en attente de révision
    APPROVED,  // KYC validé — accès complet
    REJECTED   // KYC rejeté (cf. kycRejectionReason)
}
