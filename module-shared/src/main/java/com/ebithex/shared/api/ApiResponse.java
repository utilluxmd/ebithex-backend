package com.ebithex.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;
    private String errorCode;

    /** Détail des erreurs de validation, champ par champ. Présent uniquement sur les erreurs 400 de validation. */
    private Map<String, String> details;

    /** Horodatage UTC de la réponse. */
    private Instant timestamp;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .errorCode(errorCode)
                .timestamp(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> validationError(Map<String, String> fieldErrors) {
        return ApiResponse.<T>builder()
                .success(false)
                .message("Les données envoyées sont invalides. Vérifiez les champs indiqués.")
                .errorCode("VALIDATION_ERROR")
                .details(fieldErrors)
                .timestamp(Instant.now())
                .build();
    }
}
