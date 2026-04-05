package com.ebithex.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Catalogue centralisé de tous les codes d'erreur de l'application.
 * Chaque code porte son statut HTTP et un message par défaut en français.
 * Usage : throw new EbithexException(ErrorCode.MERCHANT_NOT_FOUND);
 */
@Getter
public enum ErrorCode {

    // ── Authentification & Tokens ─────────────────────────────────────────
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED,
            "Identifiants invalides"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED,
            "Token invalide ou expiré"),
    OTP_INVALID(HttpStatus.BAD_REQUEST,
            "Code OTP invalide"),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST,
            "Code OTP expiré. Veuillez en demander un nouveau"),
    LOGIN_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
            "Nombre maximum de tentatives de connexion atteint. Compte verrouillé pendant 15 minutes"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN,
            "Ce compte est désactivé. Contactez le support"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED,
            "Accès non autorisé"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN,
            "Vous n'avez pas les permissions nécessaires pour effectuer cette action"),
    TWO_FACTOR_REQUIRED(HttpStatus.FORBIDDEN,
            "L'authentification à deux facteurs (2FA) est obligatoire pour ce rôle et ne peut pas être désactivée"),

    // ── Marchands & Utilisateurs ──────────────────────────────────────────
    MERCHANT_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Marchand introuvable"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Utilisateur introuvable"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT,
            "Un compte avec cet email existe déjà"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT,
            "Un compte avec cet email existe déjà"),
    CANNOT_DEACTIVATE_SELF(HttpStatus.BAD_REQUEST,
            "Vous ne pouvez pas désactiver votre propre compte"),

    // ── Clés API ──────────────────────────────────────────────────────────
    API_KEY_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Clé API introuvable"),
    API_KEY_INACTIVE(HttpStatus.FORBIDDEN,
            "Cette clé API est inactive ou révoquée"),
    INVALID_SCOPES(HttpStatus.BAD_REQUEST,
            "Les scopes spécifiés sont invalides ou absents"),
    INVALID_EXPIRY(HttpStatus.BAD_REQUEST,
            "La date d'expiration doit être dans le futur"),
    INVALID_ROTATION_POLICY(HttpStatus.BAD_REQUEST,
            "Politique de rotation de clé API invalide"),

    // ── Transactions ──────────────────────────────────────────────────────
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Transaction introuvable"),
    DUPLICATE_TRANSACTION(HttpStatus.CONFLICT,
            "Cette transaction a déjà été traitée (idempotence)"),
    DUPLICATE_MERCHANT_REFERENCE(HttpStatus.CONFLICT,
            "Cette référence marchand est déjà utilisée pour une autre transaction"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST,
            "Le montant est invalide. Vérifiez qu'il est positif et dans les limites autorisées"),
    CANCEL_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
            "Cette transaction ne peut pas être annulée dans son état actuel"),
    REFUND_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
            "Cette transaction ne peut pas être remboursée dans son état actuel"),
    INVALID_STATUS(HttpStatus.BAD_REQUEST,
            "Statut invalide pour cette opération"),

    // ── Limites ───────────────────────────────────────────────────────────
    DAILY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST,
            "La limite quotidienne de transactions a été atteinte"),
    MONTHLY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST,
            "La limite mensuelle de transactions a été atteinte"),

    // ── Opérateurs Mobile Money ───────────────────────────────────────────
    OPERATOR_NOT_SUPPORTED(HttpStatus.BAD_REQUEST,
            "Cet opérateur Mobile Money n'est pas supporté"),
    OPERATOR_NOT_DETECTED(HttpStatus.BAD_REQUEST,
            "Impossible de détecter l'opérateur à partir du numéro fourni. Vérifiez le format du numéro"),
    COUNTRY_REQUIRED(HttpStatus.BAD_REQUEST,
            "Le code pays est requis pour identifier l'opérateur"),

    // ── Wallet ────────────────────────────────────────────────────────────
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST,
            "Solde insuffisant pour effectuer cette opération"),
    WITHDRAWAL_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Demande de retrait introuvable"),
    WITHDRAWAL_ALREADY_PROCESSED(HttpStatus.BAD_REQUEST,
            "Ce retrait a déjà été traité"),
    WITHDRAWAL_INVALID_STATE(HttpStatus.BAD_REQUEST,
            "Ce retrait ne peut pas être modifié dans son état actuel"),
    INVALID_TRANSFER(HttpStatus.BAD_REQUEST,
            "Transfert invalide. Vérifiez les comptes source et destination"),

    // ── Payouts ───────────────────────────────────────────────────────────
    PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Paiement sortant introuvable"),
    BULK_PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Lot de paiements sortants introuvable"),
    BULK_PAYOUT_INVALID_STATE(HttpStatus.BAD_REQUEST,
            "Ce lot de paiements ne peut pas être traité dans son état actuel"),

    // ── Frais ─────────────────────────────────────────────────────────────
    FEE_RULE_NOT_FOUND(HttpStatus.BAD_REQUEST,
            "Aucune règle de frais configurée pour cet opérateur ou ce marchand"),
    INSUFFICIENT_OPERATOR_FLOAT(HttpStatus.BAD_REQUEST,
            "Float opérateur insuffisant pour traiter ce paiement. Réapprovisionnement requis"),

    // ── Webhooks ──────────────────────────────────────────────────────────
    WEBHOOK_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Webhook introuvable"),
    WEBHOOK_INACTIVE(HttpStatus.BAD_REQUEST,
            "Cet endpoint webhook est désactivé"),

    // ── KYC & Documents ───────────────────────────────────────────────────
    KYC_INVALID_STATE(HttpStatus.BAD_REQUEST,
            "Le dossier KYC ne peut pas être modifié dans son état actuel"),
    KYC_ALREADY_APPROVED(HttpStatus.BAD_REQUEST,
            "Le dossier KYC a déjà été approuvé"),
    KYC_INCOMPLETE_DOSSIER(HttpStatus.BAD_REQUEST,
            "Le dossier KYC est incomplet. Tous les documents requis doivent être fournis"),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Document introuvable"),
    DOCUMENT_LOCKED(HttpStatus.BAD_REQUEST,
            "Ce document est verrouillé et ne peut pas être supprimé"),
    DUPLICATE_DOCUMENT(HttpStatus.CONFLICT,
            "Un document de ce type a déjà été soumis pour ce dossier KYC"),

    // ── Fichiers uploadés ─────────────────────────────────────────────────
    EMPTY_FILE(HttpStatus.BAD_REQUEST,
            "Le fichier envoyé est vide"),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST,
            "La taille du fichier dépasse la limite autorisée"),
    INVALID_MIME_TYPE(HttpStatus.BAD_REQUEST,
            "Le type de fichier n'est pas accepté. Formats acceptés : PDF, JPEG, PNG"),
    READ_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
            "Erreur lors de la lecture du fichier"),
    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
            "Erreur lors du stockage du fichier. Veuillez réessayer"),

    // ── AML (Anti-Money Laundering) ───────────────────────────────────────
    AML_BLOCKED(HttpStatus.FORBIDDEN,
            "Cette transaction a été bloquée par les contrôles de lutte contre le blanchiment d'argent"),
    AML_ALREADY_RESOLVED(HttpStatus.BAD_REQUEST,
            "Cette alerte AML a déjà été résolue"),
    AML_ALERT_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Alerte AML introuvable"),

    // ── Litiges ───────────────────────────────────────────────────────────
    DISPUTE_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Litige introuvable"),
    DISPUTE_ALREADY_EXISTS(HttpStatus.CONFLICT,
            "Un litige existe déjà pour cette transaction"),
    DISPUTE_CANNOT_CANCEL(HttpStatus.BAD_REQUEST,
            "Ce litige ne peut pas être annulé dans son état actuel"),
    DISPUTE_INVALID_STATUS(HttpStatus.BAD_REQUEST,
            "Statut de litige invalide"),
    DISPUTE_INVALID_TRANSITION(HttpStatus.BAD_REQUEST,
            "Transition de statut non autorisée pour ce litige"),

    // ── Règlement & Réconciliation ────────────────────────────────────────
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Batch de règlement introuvable"),
    SETTLEMENT_INVALID_STATUS(HttpStatus.BAD_REQUEST,
            "Ce règlement ne peut pas être modifié dans son état actuel"),
    STATEMENT_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Relevé de réconciliation introuvable"),
    STATEMENT_ALREADY_EXISTS(HttpStatus.CONFLICT,
            "Un relevé existe déjà pour cet opérateur et cette période"),
    INVALID_CSV(HttpStatus.BAD_REQUEST,
            "Le fichier CSV est invalide ou mal formaté. Vérifiez la structure du fichier"),

    // ── Requêtes génériques ───────────────────────────────────────────────
    INVALID_REQUEST(HttpStatus.BAD_REQUEST,
            "La requête est invalide");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
