package com.ebithex.shared.exception;

import lombok.Getter;

@Getter
public class EbithexException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Utilise le message par défaut défini dans l'enum ErrorCode.
     */
    public EbithexException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * Permet de surcharger le message avec un contexte dynamique
     * (ex. : identifiant de la ressource introuvable).
     */
    public EbithexException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Retourne le nom du code (ex. : "MERCHANT_NOT_FOUND") pour la réponse API. */
    public String getCode() {
        return errorCode.name();
    }
}
