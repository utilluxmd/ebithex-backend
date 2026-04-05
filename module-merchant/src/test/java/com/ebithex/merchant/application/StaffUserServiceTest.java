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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour StaffUserService — gestion des utilisateurs back-office.
 * Focuses on the mandatory 2FA enforcement logic for ADMIN / SUPER_ADMIN roles.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffUserService — 2FA obligatoire pour ADMIN/SUPER_ADMIN")
class StaffUserServiceTest {

    @Mock private StaffUserRepository   staffUserRepository;
    @Mock private PasswordEncoder       passwordEncoder;
    @Mock private JwtService            jwtService;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private LoginAttemptService   loginAttemptService;
    @Mock private AuditLogService       auditLogService;
    @Mock private TwoFactorService      twoFactorService;

    private StaffUserService service;

    @BeforeEach
    void setUp() {
        service = new StaffUserService(
            staffUserRepository, passwordEncoder, jwtService,
            tokenBlacklistService, loginAttemptService, auditLogService, twoFactorService
        );
    }

    // ── create() — 2FA obligatoire ────────────────────────────────────────────

    @ParameterizedTest(name = "create() ADMIN/SUPER_ADMIN — twoFactorEnabled forcé à true même si request=false (role={0})")
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("create() — ADMIN/SUPER_ADMIN : 2FA toujours activé quelle que soit la requête")
    void create_adminRole_twoFactorForcedTrue(Role role) {
        StaffUserRequest request = new StaffUserRequest(
            "admin@ebithex.com", role, null, "SecurePass1!", false // twoFactorEnabled = false
        );
        UUID createdBy = UUID.randomUUID();
        UUID userId    = UUID.randomUUID();

        when(staffUserRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(staffUserRepository.save(any(StaffUser.class))).thenAnswer(inv -> {
            StaffUser u = inv.getArgument(0);
            u.setId(userId);
            return u;
        });

        StaffUserResponse response = service.create(request, createdBy);

        // Vérifier que le StaffUser sauvegardé a bien twoFactorEnabled = true
        verify(staffUserRepository).save(
            org.mockito.ArgumentMatchers.argThat(u -> u.isTwoFactorEnabled())
        );
    }

    @Test
    @DisplayName("create() — rôle COMPLIANCE avec twoFactorEnabled=false → conserve false")
    void create_complianceRole_twoFactorOptional() {
        StaffUserRequest request = new StaffUserRequest(
            "compliance@ebithex.com", Role.COMPLIANCE, null, "SecurePass1!", false
        );
        UUID userId = UUID.randomUUID();

        when(staffUserRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(staffUserRepository.save(any(StaffUser.class))).thenAnswer(inv -> {
            StaffUser u = inv.getArgument(0);
            u.setId(userId);
            return u;
        });

        service.create(request, UUID.randomUUID());

        verify(staffUserRepository).save(
            org.mockito.ArgumentMatchers.argThat(u -> !u.isTwoFactorEnabled())
        );
    }

    // ── update() — 2FA ne peut pas être désactivé ────────────────────────────

    @ParameterizedTest(name = "update() — désactiver 2FA pour {0} → TWO_FACTOR_REQUIRED")
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("update() — twoFactorEnabled=false pour ADMIN/SUPER_ADMIN → exception TWO_FACTOR_REQUIRED")
    void update_disableTwoFactor_adminRole_throws(Role role) {
        UUID id = UUID.randomUUID();
        StaffUser existing = StaffUser.builder()
            .id(id).email("admin@ebithex.com").role(role)
            .hashedPassword("hashed").active(true).twoFactorEnabled(true)
            .build();

        when(staffUserRepository.findById(id)).thenReturn(Optional.of(existing));

        StaffUserUpdateRequest request = new StaffUserUpdateRequest(null, null, null, false);

        assertThatThrownBy(() -> service.update(id, request, UUID.randomUUID()))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TWO_FACTOR_REQUIRED));
    }

    @Test
    @DisplayName("update() — désactiver 2FA pour SUPPORT → autorisé")
    void update_disableTwoFactor_supportRole_allowed() {
        UUID id = UUID.randomUUID();
        StaffUser existing = StaffUser.builder()
            .id(id).email("support@ebithex.com").role(Role.SUPPORT)
            .hashedPassword("hashed").active(true).twoFactorEnabled(true)
            .build();

        when(staffUserRepository.findById(id)).thenReturn(Optional.of(existing));
        when(staffUserRepository.save(any(StaffUser.class))).thenAnswer(inv -> inv.getArgument(0));

        StaffUserUpdateRequest request = new StaffUserUpdateRequest(null, null, null, false);

        // Ne doit pas lever d'exception
        StaffUserResponse response = service.update(id, request, UUID.randomUUID());
        assertThat(response).isNotNull();
    }

    // ── login() — 2FA forcé pour ADMIN/SUPER_ADMIN ───────────────────────────

    @ParameterizedTest(name = "login() — {0} avec twoFactorEnabled=false en DB → 2FA quand même exigé")
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("login() — ADMIN/SUPER_ADMIN sans 2FA en DB → 2FA forcé et DB corrigée")
    void login_adminWithout2faInDb_forces2fa(Role role) {
        StaffUser user = StaffUser.builder()
            .id(UUID.randomUUID()).email("admin@ebithex.com").role(role)
            .hashedPassword("hashed").active(true)
            .twoFactorEnabled(false) // flag absent/incorrect en DB
            .build();

        when(staffUserRepository.findByEmail("admin@ebithex.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(twoFactorService.initiateOtp("admin@ebithex.com")).thenReturn("temp-token-abc");
        when(staffUserRepository.save(any(StaffUser.class))).thenReturn(user);

        Map<String, String> result = service.login("admin@ebithex.com", "password");

        assertThat(result).containsKey("requiresTwoFactor");
        assertThat(result.get("requiresTwoFactor")).isEqualTo("true");
        assertThat(result).containsKey("tempToken");

        // Le flag doit être corrigé en DB
        verify(staffUserRepository).save(
            org.mockito.ArgumentMatchers.argThat(u -> u.isTwoFactorEnabled())
        );
    }

    @Test
    @DisplayName("login() — COMPLIANCE sans 2FA → token émis directement (pas de 2FA)")
    void login_complianceWithout2fa_issuesTokenDirectly() {
        UUID userId = UUID.randomUUID();
        StaffUser user = StaffUser.builder()
            .id(userId).email("compliance@ebithex.com").role(Role.COMPLIANCE)
            .hashedPassword("hashed").active(true).twoFactorEnabled(false)
            .build();

        when(staffUserRepository.findByEmail("compliance@ebithex.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtService.generateOperatorToken(any(), anyString(), any(), any())).thenReturn("jwt-token");

        Map<String, String> result = service.login("compliance@ebithex.com", "password");

        assertThat(result).containsKey("accessToken");
        assertThat(result).doesNotContainKey("requiresTwoFactor");
    }

    // ── login() — compte désactivé ────────────────────────────────────────────

    @Test
    @DisplayName("login() — compte désactivé → ACCOUNT_DISABLED")
    void login_disabledAccount_throws() {
        StaffUser user = StaffUser.builder()
            .id(UUID.randomUUID()).email("user@ebithex.com").role(Role.SUPPORT)
            .hashedPassword("hashed").active(false).twoFactorEnabled(false)
            .build();

        when(staffUserRepository.findByEmail("user@ebithex.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.login("user@ebithex.com", "password"))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED));
    }

    // ── create() — email déjà existant ────────────────────────────────────────

    @Test
    @DisplayName("create() — email en double → EMAIL_ALREADY_EXISTS")
    void create_duplicateEmail_throws() {
        StaffUserRequest request = new StaffUserRequest(
            "existing@ebithex.com", Role.SUPPORT, null, "SecurePass1!", false
        );
        when(staffUserRepository.existsByEmail("existing@ebithex.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, UUID.randomUUID()))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));
    }

    // ── update() — utilisateur non trouvé ────────────────────────────────────

    @Test
    @DisplayName("update() — UUID inconnu → USER_NOT_FOUND")
    void update_unknownId_throws() {
        UUID id = UUID.randomUUID();
        when(staffUserRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, new StaffUserUpdateRequest(null, null, null, null), UUID.randomUUID()))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }
}
