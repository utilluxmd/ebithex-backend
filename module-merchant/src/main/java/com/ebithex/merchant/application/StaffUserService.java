package com.ebithex.merchant.application;

import com.ebithex.merchant.domain.StaffUser;
import com.ebithex.merchant.dto.StaffUserRequest;
import com.ebithex.merchant.dto.StaffUserResponse;
import com.ebithex.merchant.dto.StaffUserUpdateRequest;
import com.ebithex.merchant.infrastructure.StaffUserRepository;
import com.ebithex.shared.audit.AuditLogService;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.security.JwtService;
import com.ebithex.shared.security.LoginAttemptService;
import com.ebithex.shared.security.Role;
import com.ebithex.shared.security.TokenBlacklistService;
import com.ebithex.shared.security.TwoFactorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gestion des utilisateurs back-office Ebithex (StaffUser).
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Authentification : login, 2FA (OTP email), logout</li>
 *   <li>CRUD : création, mise à jour, désactivation, reset mot de passe</li>
 * </ul>
 *
 * <p>À ne pas confondre avec {@code MerchantService} (comptes marchands externes)
 * ni avec les connecteurs {@code MobileMoneyOperator} (Orange, MTN, Wave…).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StaffUserService {

    private final StaffUserRepository   staffUserRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtService            jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final LoginAttemptService   loginAttemptService;
    private final AuditLogService       auditLogService;
    private final TwoFactorService      twoFactorService;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_PWD_CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#$!";

    /** Rôles pour lesquels le 2FA est obligatoire et ne peut pas être désactivé. */
    private static final java.util.Set<Role> TWO_FACTOR_MANDATORY_ROLES =
        java.util.Set.of(Role.ADMIN, Role.SUPER_ADMIN);

    // ── Authentification ──────────────────────────────────────────────────────

    public Map<String, String> login(String email, String password) {
        loginAttemptService.checkNotLocked(email);

        StaffUser user = staffUserRepository.findByEmail(email)
            .orElseThrow(() -> {
                loginAttemptService.recordFailure(email);
                return new EbithexException(ErrorCode.INVALID_CREDENTIALS, "Identifiants invalides");
            });

        if (!passwordEncoder.matches(password, user.getHashedPassword())) {
            loginAttemptService.recordFailure(email);
            throw new EbithexException(ErrorCode.INVALID_CREDENTIALS, "Identifiants invalides");
        }
        if (!user.isActive()) {
            throw new EbithexException(ErrorCode.ACCOUNT_DISABLED, "Compte désactivé");
        }

        loginAttemptService.recordSuccess(email);

        // 2FA obligatoire pour ADMIN et SUPER_ADMIN, quelle que soit la config du compte.
        // Si le flag n'est pas déjà activé en DB, on le force ici pour corriger l'état.
        boolean requires2FA = user.isTwoFactorEnabled() || isTwoFactorMandatory(user.getRole());
        if (requires2FA) {
            if (!user.isTwoFactorEnabled()) {
                user.setTwoFactorEnabled(true);
                staffUserRepository.save(user);
                log.warn("2FA auto-activé pour {} ({}) — flag manquant en DB corrigé",
                    user.getEmail(), user.getRole());
            }
            String tempToken = twoFactorService.initiateOtp(user.getEmail());
            log.info("2FA requis pour: {} ({})", user.getEmail(), user.getRole());
            return Map.of(
                "requiresTwoFactor", "true",
                "tempToken",         tempToken,
                "expiresInSeconds",  "300"
            );
        }

        return issueToken(user);
    }

    public Map<String, String> verifyOtp(String tempToken, String code) {
        String email = twoFactorService.verifyOtp(tempToken, code);
        StaffUser user = staffUserRepository.findByEmail(email)
            .orElseThrow(() -> new EbithexException(ErrorCode.USER_NOT_FOUND, "Utilisateur introuvable"));
        log.info("OTP vérifié — connexion finalisée: {} ({})", email, user.getRole());
        auditLogService.record("STAFF_USER_LOGIN_2FA", "StaffUser", user.getId().toString(),
            "{\"role\":\"" + user.getRole().name() + "\"}");
        return issueToken(user);
    }

    public void logout(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtService.validateToken(token)) {
                String userId = jwtService.extractMerchantId(token).toString();
                tokenBlacklistService.revoke(jwtService.getJti(token), jwtService.getExpiration(token));
                auditLogService.record("STAFF_USER_LOGOUT", "StaffUser", userId, null);
            }
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    public StaffUserResponse create(StaffUserRequest request, UUID createdBy) {
        if (staffUserRepository.existsByEmail(request.email())) {
            throw new EbithexException(ErrorCode.EMAIL_ALREADY_EXISTS,
                "Un utilisateur avec cet email existe déjà : " + request.email());
        }
        if (request.role() == Role.COUNTRY_ADMIN && (request.country() == null || request.country().isBlank())) {
            throw new EbithexException(ErrorCode.COUNTRY_REQUIRED,
                "Le champ country est obligatoire pour le rôle COUNTRY_ADMIN");
        }

        // Pour ADMIN et SUPER_ADMIN : 2FA obligatoire, la valeur de la requête est ignorée.
        boolean twoFactorEnabled = isTwoFactorMandatory(request.role()) || request.twoFactorEnabled();

        StaffUser user = StaffUser.builder()
            .email(request.email())
            .hashedPassword(passwordEncoder.encode(request.password()))
            .role(request.role())
            .country(request.country())
            .active(true)
            .twoFactorEnabled(twoFactorEnabled)
            .build();

        user = staffUserRepository.save(user);
        log.info("StaffUser créé: {} ({}) par {}", user.getEmail(), user.getRole(), createdBy);
        auditLogService.record("STAFF_USER_CREATED", "StaffUser", user.getId().toString(),
            "{\"role\":\"" + user.getRole().name() + "\",\"createdBy\":\"" + createdBy + "\"}");

        return StaffUserResponse.from(user);
    }

    public Page<StaffUserResponse> list(Pageable pageable) {
        return staffUserRepository.findAll(pageable).map(StaffUserResponse::from);
    }

    public StaffUserResponse findById(UUID id) {
        return StaffUserResponse.from(getOrThrow(id));
    }

    @Transactional
    public StaffUserResponse update(UUID id, StaffUserUpdateRequest request, UUID updatedBy) {
        StaffUser user = getOrThrow(id);

        if (request.role() != null) {
            if (request.role() == Role.COUNTRY_ADMIN) {
                String country = request.country() != null ? request.country() : user.getCountry();
                if (country == null || country.isBlank()) {
                    throw new EbithexException(ErrorCode.COUNTRY_REQUIRED,
                        "Le champ country est obligatoire pour le rôle COUNTRY_ADMIN");
                }
            }
            user.setRole(request.role());
        }
        if (request.country() != null)          user.setCountry(request.country());
        if (request.active() != null)            user.setActive(request.active());
        if (request.twoFactorEnabled() != null) {
            if (Boolean.FALSE.equals(request.twoFactorEnabled()) && isTwoFactorMandatory(user.getRole())) {
                throw new EbithexException(ErrorCode.TWO_FACTOR_REQUIRED,
                    "Le 2FA ne peut pas être désactivé pour le rôle " + user.getRole().name());
            }
            user.setTwoFactorEnabled(request.twoFactorEnabled());
        }

        user = staffUserRepository.save(user);
        log.info("StaffUser mis à jour: {} par {}", id, updatedBy);
        auditLogService.record("STAFF_USER_UPDATED", "StaffUser", id.toString(),
            "{\"updatedBy\":\"" + updatedBy + "\"}");

        return StaffUserResponse.from(user);
    }

    @Transactional
    public void deactivate(UUID id, UUID requestedBy) {
        if (id.equals(requestedBy)) {
            throw new EbithexException(ErrorCode.CANNOT_DEACTIVATE_SELF,
                "Vous ne pouvez pas désactiver votre propre compte");
        }
        StaffUser user = getOrThrow(id);
        user.setActive(false);
        staffUserRepository.save(user);
        log.warn("StaffUser désactivé: {} ({}) par {}", user.getEmail(), user.getRole(), requestedBy);
        auditLogService.record("STAFF_USER_DEACTIVATED", "StaffUser", id.toString(),
            "{\"deactivatedBy\":\"" + requestedBy + "\"}");
    }

    /**
     * Génère un nouveau mot de passe temporaire et le retourne en clair.
     * L'administrateur est responsable de le communiquer à l'utilisateur.
     */
    @Transactional
    public String resetPassword(UUID id, UUID requestedBy) {
        StaffUser user = getOrThrow(id);
        String tempPassword = generateTempPassword();
        user.setHashedPassword(passwordEncoder.encode(tempPassword));
        staffUserRepository.save(user);
        log.warn("Mot de passe réinitialisé pour: {} par {}", user.getEmail(), requestedBy);
        auditLogService.record("STAFF_USER_PASSWORD_RESET", "StaffUser", id.toString(),
            "{\"requestedBy\":\"" + requestedBy + "\"}");
        return tempPassword;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> issueToken(StaffUser user) {
        List<String> roles = List.of(user.getRole().name());
        String token = jwtService.generateOperatorToken(
            user.getId(), user.getEmail(), roles, user.getCountry());
        log.info("Token émis pour: {} ({})", user.getEmail(), user.getRole());
        auditLogService.record("STAFF_USER_LOGIN", "StaffUser", user.getId().toString(),
            "{\"role\":\"" + user.getRole().name() + "\"}");
        return Map.of(
            "accessToken", token,
            "staffUserId", user.getId().toString(),
            "role",        user.getRole().name()
        );
    }

    /** Retourne {@code true} si le rôle impose le 2FA obligatoirement. */
    private boolean isTwoFactorMandatory(Role role) {
        return TWO_FACTOR_MANDATORY_ROLES.contains(role);
    }

    private StaffUser getOrThrow(UUID id) {
        return staffUserRepository.findById(id)
            .orElseThrow(() -> new EbithexException(ErrorCode.USER_NOT_FOUND,
                "Utilisateur back-office introuvable : " + id));
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(TEMP_PWD_CHARS.charAt(RANDOM.nextInt(TEMP_PWD_CHARS.length())));
        }
        return sb.toString();
    }
}