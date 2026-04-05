package com.ebithex.auth.api;

import com.ebithex.merchant.application.MerchantService;
import com.ebithex.merchant.dto.LoginRequest;
import com.ebithex.merchant.dto.MerchantRegistrationRequest;
import com.ebithex.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentification", description = "Inscription, connexion et gestion des tokens marchands")
public class AuthController {

    private final MerchantService merchantService;

    @PostMapping("/register")
    @Operation(summary = "Créer un compte marchand")
    public ResponseEntity<ApiResponse<Map<String, String>>> register(
            @Valid @RequestBody MerchantRegistrationRequest request) {
        Map<String, String> result = merchantService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Compte créé", result));
    }

    @PostMapping("/login")
    @Operation(summary = "Se connecter et obtenir un JWT")
    public ResponseEntity<ApiResponse<Map<String, String>>> login(
            @Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            merchantService.login(req.getEmail(), req.getPassword())));
    }

    /**
     * Révoque l'access token courant (header Authorization).
     * Si refreshToken est fourni dans le body, il est également révoqué.
     */
    @PostMapping("/logout")
    @Operation(summary = "Déconnexion — révocation des tokens")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            @RequestBody(required = false) Map<String, String> body) {
        String refreshToken = body != null ? body.get("refreshToken") : null;
        merchantService.logout(request.getHeader("Authorization"), refreshToken);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Émet une nouvelle paire access/refresh depuis un refresh token valide.
     * Le refresh token fourni est immédiatement révoqué (rotation).
     */
    @PostMapping("/refresh")
    @Operation(summary = "Renouveler les tokens via refresh token")
    public ResponseEntity<ApiResponse<Map<String, String>>> refresh(
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(
            merchantService.refreshToken(body.get("refreshToken"))));
    }

}
