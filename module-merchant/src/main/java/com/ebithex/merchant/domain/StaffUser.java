package com.ebithex.merchant.domain;

import com.ebithex.shared.security.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Compte interne Ebithex (back-office, admin, support, finance…).
 *
 * <p>À ne pas confondre avec :
 * <ul>
 *   <li>{@code Merchant} — compte marchand externe</li>
 *   <li>{@code MobileMoneyOperator} — connecteur technique vers Orange/MTN/Wave</li>
 * </ul>
 *
 * <p>Roles disponibles : SUPER_ADMIN · ADMIN · COUNTRY_ADMIN · FINANCE ·
 * RECONCILIATION · COMPLIANCE · SUPPORT
 */
@Entity
@Table(name = "staff_users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StaffUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String hashedPassword;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    /** Code pays ISO-3166-1 alpha-2 — non-null uniquement pour COUNTRY_ADMIN. */
    @Column(length = 3)
    private String country;

    @Column(nullable = false)
    private boolean active = true;

    /** Si true, une vérification OTP par email est requise après le mot de passe. */
    @Column(nullable = false)
    private boolean twoFactorEnabled = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}