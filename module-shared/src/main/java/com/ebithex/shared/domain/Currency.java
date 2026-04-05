package com.ebithex.shared.domain;

/**
 * Devises africaines et internationales supportées par Ebithex.
 *
 * Organisation par zone monétaire :
 *  - Zone UEMOA    : XOF (CFA Ouest)
 *  - Zone CEMAC    : XAF (CFA Centre)
 *  - Afrique de l'Ouest : NGN, GHS, GMD, SLL, LRD, GNF, CVE
 *  - Afrique de l'Est   : KES, TZS, UGX, RWF, ETB, SOS, DJF, ERN, SCR, MUR, SSP
 *  - Afrique Australe   : ZAR, ZMW, MWK, MZN, AOA, BWP, NAD, SZL, LSL
 *  - Afrique du Nord    : MAD, EGP, DZD, TND, LYD, SDG
 *  - Îles africaines    : MGA, STN, KMF, CDF
 *  - International      : USD, EUR, GBP
 */
public enum Currency {

    // ── Zone UEMOA (CFA Ouest) ────────────────────────────────────────────────
    /** Franc CFA UEMOA — CI, SN, BJ, BF, ML, NE, TG, GW */
    XOF,

    // ── Zone CEMAC (CFA Centre) ───────────────────────────────────────────────
    /** Franc CFA CEMAC — CM, CF, TD, CG, GQ, GA */
    XAF,

    // ── Afrique de l'Ouest ────────────────────────────────────────────────────
    /** Naira nigérian */
    NGN,
    /** Cedi ghanéen */
    GHS,
    /** Dalasi gambien */
    GMD,
    /** Leone sierra-léonais */
    SLL,
    /** Dollar libérien */
    LRD,
    /** Franc guinéen */
    GNF,
    /** Escudo cap-verdien */
    CVE,

    // ── Afrique de l'Est ──────────────────────────────────────────────────────
    /** Shilling kényan */
    KES,
    /** Shilling tanzanien */
    TZS,
    /** Shilling ougandais */
    UGX,
    /** Franc rwandais */
    RWF,
    /** Birr éthiopien */
    ETB,
    /** Shilling somalien */
    SOS,
    /** Franc djiboutien */
    DJF,
    /** Nakfa érythréen */
    ERN,
    /** Roupie des Seychelles */
    SCR,
    /** Roupie mauricienne */
    MUR,
    /** Pound sud-soudanais */
    SSP,

    // ── Afrique Australe ──────────────────────────────────────────────────────
    /** Rand sud-africain */
    ZAR,
    /** Kwacha zambien */
    ZMW,
    /** Kwacha malawien */
    MWK,
    /** Metical mozambicain */
    MZN,
    /** Kwanza angolais */
    AOA,
    /** Pula botswanais */
    BWP,
    /** Dollar namibien */
    NAD,
    /** Lilangeni swazilandais */
    SZL,
    /** Loti lesothan */
    LSL,

    // ── Afrique du Nord ───────────────────────────────────────────────────────
    /** Dirham marocain */
    MAD,
    /** Livre égyptienne */
    EGP,
    /** Dinar algérien */
    DZD,
    /** Dinar tunisien */
    TND,
    /** Dinar libyen */
    LYD,
    /** Livre soudanaise */
    SDG,

    // ── Îles africaines ───────────────────────────────────────────────────────
    /** Ariary malgache */
    MGA,
    /** Dobra de São Tomé-et-Príncipe */
    STN,
    /** Franc comorien */
    KMF,
    /** Franc congolais (RDC) */
    CDF,

    // ── International ─────────────────────────────────────────────────────────
    /** Dollar américain */
    USD,
    /** Euro */
    EUR,
    /** Livre sterling */
    GBP
}