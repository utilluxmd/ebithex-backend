package com.ebithex.shared.apikey;

/**
 * Périmètres d'autorisation d'une clé API marchand.
 *
 * <p>Une clé peut porter un sous-ensemble de ces scopes. Lors de chaque requête,
 * {@code ScopeGuard} vérifie que le scope requis par l'endpoint est présent.
 *
 * <p>{@code FULL_ACCESS} est un scope spécial qui couvre implicitement tous les autres.
 * Toutes les clés créées avant l'introduction du système de scopes reçoivent
 * {@code FULL_ACCESS} lors de la migration — comportement inchangé.
 */
public enum ApiKeyScope {

    /** Initier des paiements : POST /v1/payments, /v1/payments/bulk */
    PAYMENTS_WRITE,

    /** Lire les transactions : GET /v1/payments/**, /v1/transactions/** */
    PAYMENTS_READ,

    /** Initier des virements : POST /v1/payouts, /v1/payouts/bulk */
    PAYOUTS_WRITE,

    /** Lire les virements : GET /v1/payouts/** */
    PAYOUTS_READ,

    /** Lire les webhooks : GET /v1/webhooks/** */
    WEBHOOKS_READ,

    /** Lire le profil marchand : GET /v1/merchants/me */
    PROFILE_READ,

    /**
     * Accès complet — équivalent à tous les autres scopes combinés.
     * Scope par défaut à la création d'une clé.
     */
    FULL_ACCESS
}
