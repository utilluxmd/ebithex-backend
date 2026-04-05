package com.ebithex.shared.security;

/**
 * Plans de rate limiting par tier marchand.
 *
 * Deux fenêtres glissantes indépendantes :
 *  - Minute : protection anti-burst (rafale soudaine)
 *  - Heure  : quota soutenu (usage total)
 *
 * Attribution des plans :
 *  MERCHANT_KYC_VERIFIED → PREMIUM
 *  MERCHANT, AGENT       → STANDARD
 *  Unauthenticated       → ANONYMOUS (par IP)
 */
public enum RateLimitPlan {

    //                  req/min  win(s)  req/h   win(s)
    ANONYMOUS  (        10,      60,       300,  3_600),
    STANDARD   (        60,      60,     3_000,  3_600),
    PREMIUM    (       300,      60,    10_000,  3_600);

    public final int minuteLimit;
    public final int minuteWindow;  // secondes
    public final int hourLimit;
    public final int hourWindow;    // secondes

    RateLimitPlan(int minuteLimit, int minuteWindow, int hourLimit, int hourWindow) {
        this.minuteLimit  = minuteLimit;
        this.minuteWindow = minuteWindow;
        this.hourLimit    = hourLimit;
        this.hourWindow   = hourWindow;
    }
}
