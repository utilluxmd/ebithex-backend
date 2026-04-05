package com.ebithex.payment.api;

import com.ebithex.payment.application.KeyRotationService;
import com.ebithex.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint interne de rotation des clés AES.
 *
 * <p>Accessible uniquement par les super-administrateurs Ebithex (rôle SUPER_ADMIN).
 * Non exposé dans la documentation Swagger publique.
 *
 * <p>Voir {@link KeyRotationService} pour la procédure complète de rotation.
 */
@RestController
@RequestMapping("/internal/admin/key-rotation")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin — Key Rotation", description = "Rotation des clés de chiffrement AES (usage interne)")
@SecurityRequirement(name = "bearerAuth")
public class KeyRotationController {

    private final KeyRotationService keyRotationService;

    /**
     * Lance la rotation des clés AES sur toutes les données chiffrées.
     *
     * <p>Opération potentiellement longue (minutes sur des millions d'enregistrements).
     * Idempotente — peut être relancée sans risque si interrompue.
     *
     * <p>Retourne le nombre d'enregistrements re-chiffrés.
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Rotation des clés AES",
        description = "Re-chiffre tous les enregistrements PII avec la clé active. "
            + "Idempotente. Durée variable selon le volume de données."
    )
    public ResponseEntity<ApiResponse<KeyRotationService.KeyRotationResult>> rotate() {
        log.warn("Rotation des clés AES déclenchée manuellement");
        KeyRotationService.KeyRotationResult result = keyRotationService.rotateAll();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}