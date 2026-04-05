package com.ebithex.config;

import com.ebithex.merchant.domain.StaffUser;
import com.ebithex.merchant.infrastructure.StaffUserRepository;
import com.ebithex.shared.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bootstrap du premier compte SUPER_ADMIN au démarrage.
 *
 * <p>Comportement :
 * <ul>
 *   <li>Si aucun SUPER_ADMIN n'existe en base → en crée un avec l'email et le mot de passe configurés</li>
 *   <li>Si un SUPER_ADMIN existe déjà → ne fait rien (idempotent)</li>
 *   <li>Si {@code ebithex.super-admin.email} ou {@code ebithex.super-admin.password} ne sont pas définis → skip silencieux</li>
 * </ul>
 *
 * <p>Variables d'environnement :
 * <pre>
 *   EBITHEX_SUPER_ADMIN_EMAIL=admin@ebithex.io
 *   EBITHEX_SUPER_ADMIN_PASSWORD=ChangeMe!SecureP@ss2026
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapRunner implements ApplicationRunner {

    private final StaffUserRepository staffUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ebithex.super-admin.email:}")
    private String adminEmail;

    @Value("${ebithex.super-admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.debug("Admin bootstrap ignoré — ebithex.super-admin.email ou ebithex.super-admin.password non configuré");
            return;
        }

        boolean superAdminExists = staffUserRepository.findByEmail(adminEmail).isPresent();
        if (superAdminExists) {
            log.info("Admin bootstrap ignoré — SUPER_ADMIN {} existe déjà", adminEmail);
            return;
        }

        if (adminPassword.length() < 12) {
            log.error("Admin bootstrap échoué — le mot de passe doit faire au moins 12 caractères");
            return;
        }

        StaffUser admin = StaffUser.builder()
                .email(adminEmail)
                .hashedPassword(passwordEncoder.encode(adminPassword))
                .role(Role.SUPER_ADMIN)
                .active(true)
                .twoFactorEnabled(true)
                .build();

        staffUserRepository.save(admin);
        log.warn("✔ SUPER_ADMIN créé au bootstrap : {} — CHANGEZ LE MOT DE PASSE après le premier login", adminEmail);
    }
}
