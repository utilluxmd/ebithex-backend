package com.ebithex.auth.api;

import com.ebithex.merchant.application.StaffUserService;
import com.ebithex.merchant.dto.StaffUserRequest;
import com.ebithex.merchant.dto.StaffUserResponse;
import com.ebithex.merchant.dto.StaffUserUpdateRequest;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Gestion des utilisateurs back-office Ebithex (CRUD).
 *
 * <p>Tous les endpoints requièrent le rôle SUPER_ADMIN, sauf la lecture (ADMIN+).
 *
 * <pre>
 * GET    /internal/staff-users            — lister les utilisateurs (ADMIN, SUPER_ADMIN)
 * GET    /internal/staff-users/{id}       — détail d'un utilisateur (ADMIN, SUPER_ADMIN)
 * POST   /internal/staff-users            — créer un utilisateur (SUPER_ADMIN)
 * PUT    /internal/staff-users/{id}       — modifier rôle/pays/statut/2FA (SUPER_ADMIN)
 * DELETE /internal/staff-users/{id}       — désactiver un utilisateur (SUPER_ADMIN)
 * POST   /internal/staff-users/{id}/reset-password — réinitialiser le mot de passe (SUPER_ADMIN)
 * </pre>
 */
@RestController
@RequestMapping("/internal/staff-users")
@RequiredArgsConstructor
@Tag(name = "Back-office — Gestion des utilisateurs (Staff)",
     description = "CRUD des comptes back-office Ebithex. Création, attribution de rôles, désactivation.")
@SecurityRequirement(name = "bearerAuth")
public class StaffUserController {

    private final StaffUserService staffUserService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
        summary = "Lister les utilisateurs back-office",
        description = "Retourne la liste paginée de tous les utilisateurs internes Ebithex."
    )
    public ResponseEntity<ApiResponse<Page<StaffUserResponse>>> list(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(staffUserService.list(pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Détail d'un utilisateur back-office")
    public ResponseEntity<ApiResponse<StaffUserResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(staffUserService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Créer un utilisateur back-office (SUPER_ADMIN)",
        description = "Crée un nouveau compte back-office avec le rôle spécifié. " +
                      "Pour COUNTRY_ADMIN, le champ `country` est obligatoire. " +
                      "Le mot de passe initial est fourni par l'administrateur."
    )
    public ResponseEntity<ApiResponse<StaffUserResponse>> create(
            @Valid @RequestBody StaffUserRequest request,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        StaffUserResponse response = staffUserService.create(request, principal.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Utilisateur créé", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Modifier un utilisateur back-office (SUPER_ADMIN)",
        description = "Met à jour le rôle, le pays, le statut actif ou la 2FA. " +
                      "Seuls les champs non-null sont modifiés."
    )
    public ResponseEntity<ApiResponse<StaffUserResponse>> update(
            @PathVariable UUID id,
            @RequestBody StaffUserUpdateRequest request,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        StaffUserResponse response = staffUserService.update(id, request, principal.id());
        return ResponseEntity.ok(ApiResponse.ok("Utilisateur mis à jour", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Désactiver un utilisateur back-office (SUPER_ADMIN)",
        description = "Désactive le compte (soft delete). " +
                      "L'utilisateur ne pourra plus se connecter mais ses données sont conservées. " +
                      "Un administrateur ne peut pas désactiver son propre compte."
    )
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        staffUserService.deactivate(id, principal.id());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Réinitialiser le mot de passe (SUPER_ADMIN)",
        description = "Génère un mot de passe temporaire et le retourne en clair. " +
                      "L'administrateur est responsable de le communiquer à l'utilisateur de façon sécurisée. " +
                      "L'utilisateur devra le changer à sa prochaine connexion."
    )
    public ResponseEntity<ApiResponse<Map<String, String>>> resetPassword(
            @PathVariable UUID id,
            @AuthenticationPrincipal EbithexPrincipal principal) {
        String tempPassword = staffUserService.resetPassword(id, principal.id());
        return ResponseEntity.ok(ApiResponse.ok(
            Map.of("temporaryPassword", tempPassword,
                   "message", "Communiquez ce mot de passe à l'utilisateur de façon sécurisée")));
    }
}