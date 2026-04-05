package com.ebithex.shared.apikey;

/**
 * Type d'une clé API.
 * Détermine le schéma PostgreSQL utilisé lors de l'authentification.
 */
public enum ApiKeyType {

    /** Clé de production — préfixe {@code ap_live_}, schéma {@code public}. */
    LIVE,

    /** Clé sandbox — préfixe {@code ap_test_}, schéma {@code sandbox}. */
    TEST
}
