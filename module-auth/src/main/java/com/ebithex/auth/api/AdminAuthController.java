package com.ebithex.auth.api;

import com.ebithex.merchant.application.StaffUserService;
import com.ebithex.merchant.dto.LoginRequest;
import com.ebithex.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentification des utilisateurs back-office Ebithex (StaffUser).
 *
 * POST /internal/auth/login             — connexion (étape 1 : email + mdp)
 * POST /internal/auth/login/verify-otp  — vérification OTP 2FA (étape 2)
 * POST /internal/auth/logout            — révocation du token courant
 */
@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
@Tag(name = "Back-office — Authentification")
public class AdminAuthController {

    private final StaffUserService staffUserService;

    @PostMapping("/login")
    @Operation(summary = "Connexion opérateur back-office")
    public ResponseEntity<ApiResponse<Map<String, String>>> login(
            @Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            staffUserService.login(req.getEmail(), req.getPassword())));
    }

    /**
     * Étape 2 du login 2FA.
     * Soumettre le tempToken retourné à l'étape 1 et le code OTP reçu par email.
     * Retourne le JWT final si le code est correct et non expiré (TTL 5 min).
     */
    @PostMapping("/login/verify-otp")
    @Operation(summary = "Vérifier le code OTP (second facteur) — retourne le JWT final")
    public ResponseEntity<ApiResponse<Map<String, String>>> verifyOtp(
            @RequestBody Map<String, String> body) {
        String tempToken = body.get("tempToken");
        String code      = body.get("code");
        if (tempToken == null || code == null) {
            throw new com.ebithex.shared.exception.EbithexException(
                    com.ebithex.shared.exception.ErrorCode.INVALID_REQUEST,
                    "Les champs tempToken et code sont obligatoires");
        }
        return ResponseEntity.ok(ApiResponse.ok(staffUserService.verifyOtp(tempToken, code)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Déconnexion opérateur — révocation du token")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        staffUserService.logout(request.getHeader("Authorization"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}