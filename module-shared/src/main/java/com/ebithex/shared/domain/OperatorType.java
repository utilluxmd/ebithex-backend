package com.ebithex.shared.domain;

/**
 * Opérateurs Mobile Money par pays.
 *
 * La granularité pays est nécessaire car chaque opérateur-pays a :
 *  - un endpoint API distinct
 *  - des credentials distincts (subscription key, merchant key, etc.)
 *  - potentiellement un flux de paiement différent
 *
 * Couverture actuelle :
 *  Zone UEMOA  : CI, SN, BJ, BF, ML, TG
 *  Zone CEMAC  : CM
 *  Afrique de l'Ouest  : NG, GH
 *  Afrique de l'Est    : KE, TZ, UG, RW
 *  Afrique Australe    : ZA
 */
public enum OperatorType {

    // ── Côte d'Ivoire (+225, XOF) ─────────────────────────────────────────────
    MTN_MOMO_CI,
    ORANGE_MONEY_CI,
    MOOV_MONEY_CI,
    WAVE_CI,

    // ── Sénégal (+221, XOF) ───────────────────────────────────────────────────
    ORANGE_MONEY_SN,
    WAVE_SN,
    FREE_MONEY_SN,

    // ── Bénin (+229, XOF) ─────────────────────────────────────────────────────
    MTN_MOMO_BJ,
    MOOV_MONEY_BJ,

    // ── Burkina Faso (+226, XOF) ──────────────────────────────────────────────
    ORANGE_MONEY_BF,
    MOOV_MONEY_BF,

    // ── Mali (+223, XOF) ──────────────────────────────────────────────────────
    ORANGE_MONEY_ML,

    // ── Togo (+228, XOF) ──────────────────────────────────────────────────────
    TMONEY_TG,
    FLOOZ_TG,

    // ── Cameroun (+237, XAF) ──────────────────────────────────────────────────
    MTN_MOMO_CM,
    ORANGE_MONEY_CM,

    // ── Nigeria (+234, NGN) ───────────────────────────────────────────────────
    MTN_MOMO_NG,
    AIRTEL_MONEY_NG,
    OPAY_NG,

    // ── Ghana (+233, GHS) ─────────────────────────────────────────────────────
    MTN_MOMO_GH,
    VODAFONE_CASH_GH,
    AIRTEL_TIGO_GH,

    // ── Kenya (+254, KES) ─────────────────────────────────────────────────────
    MPESA_KE,
    AIRTEL_MONEY_KE,

    // ── Tanzanie (+255, TZS) ──────────────────────────────────────────────────
    MPESA_TZ,
    AIRTEL_MONEY_TZ,
    TIGOPESA_TZ,

    // ── Ouganda (+256, UGX) ───────────────────────────────────────────────────
    MTN_MOMO_UG,
    AIRTEL_MONEY_UG,

    // ── Rwanda (+250, RWF) ────────────────────────────────────────────────────
    MTN_MOMO_RW,
    AIRTEL_MONEY_RW,

    // ── Afrique du Sud (+27, ZAR) ─────────────────────────────────────────────
    MTN_MOMO_ZA,

    // ── Détection automatique ─────────────────────────────────────────────────
    AUTO
}
