package com.ebithex.shared.exception;

import com.ebithex.shared.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── Erreurs métier ────────────────────────────────────────────────────

    @ExceptionHandler(EbithexException.class)
    public ResponseEntity<ApiResponse<?>> handleEbithex(EbithexException ex) {
        log.warn("EbithexException [{}]: {}", ex.getCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getCode()));
    }

    // ── Validation des requêtes (@Valid / @Validated) ─────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Valeur invalide",
                        (existing, duplicate) -> existing
                ));
        log.debug("Validation échouée sur {} champ(s) : {}", fieldErrors.size(), fieldErrors);
        return ResponseEntity.badRequest().body(ApiResponse.validationError(fieldErrors));
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex) {
        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        cv -> cv.getPropertyPath().toString(),
                        cv -> cv.getMessage() != null ? cv.getMessage() : "Valeur invalide",
                        (existing, duplicate) -> existing
                ));
        log.debug("ConstraintViolation sur {} champ(s) : {}", fieldErrors.size(), fieldErrors);
        return ResponseEntity.badRequest().body(ApiResponse.validationError(fieldErrors));
    }

    // ── Erreurs de format / type de requête ───────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Corps de requête illisible : {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(
                        "Le corps de la requête est illisible ou mal formaté. Vérifiez que le JSON est valide.",
                        "INVALID_REQUEST"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "inconnu";
        String message = String.format(
                "La valeur '%s' du paramètre '%s' est invalide. Type attendu : %s.",
                ex.getValue(), ex.getName(), expected);
        log.debug("Type mismatch : {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(message, "INVALID_REQUEST"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<?>> handleMissingParam(MissingServletRequestParameterException ex) {
        String message = String.format(
                "Le paramètre obligatoire '%s' est absent de la requête.", ex.getParameterName());
        log.debug("Paramètre manquant : {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(message, "INVALID_REQUEST"));
    }

    // ── Erreurs HTTP ──────────────────────────────────────────────────────

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(NoResourceFoundException ex) {
        log.debug("Route introuvable : {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(
                        "La ressource demandée est introuvable.",
                        "NOT_FOUND"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        String message = String.format(
                "La méthode HTTP '%s' n'est pas supportée pour cette route.", ex.getMethod());
        log.debug(message);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(message, "METHOD_NOT_ALLOWED"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<?>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.debug("Fichier trop volumineux : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(
                        "La taille du fichier dépasse la limite autorisée par le serveur.",
                        ErrorCode.FILE_TOO_LARGE.name()));
    }

    // ── Sécurité ──────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Accès refusé : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        ErrorCode.ACCESS_DENIED.getDefaultMessage(),
                        ErrorCode.ACCESS_DENIED.name()));
    }

    // ── Contraintes base de données ───────────────────────────────────────

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.error("Violation de contrainte base de données : {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "Cette opération viole une contrainte d'intégrité des données.",
                        "DATA_INTEGRITY_VIOLATION"));
    }

    // ── Erreur inattendue (catch-all) ─────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneric(Exception ex) {
        log.error("Erreur inattendue", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "Une erreur interne est survenue. Si le problème persiste, contactez le support.",
                        "INTERNAL_ERROR"));
    }
}
