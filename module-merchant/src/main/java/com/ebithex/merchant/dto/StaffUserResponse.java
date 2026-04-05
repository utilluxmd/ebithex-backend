package com.ebithex.merchant.dto;

import com.ebithex.merchant.domain.StaffUser;
import com.ebithex.shared.security.Role;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Représentation publique d'un utilisateur back-office.
 * Le mot de passe haché n'est jamais exposé.
 */
public record StaffUserResponse(
    UUID          id,
    String        email,
    Role          role,
    String        country,
    boolean       active,
    boolean       twoFactorEnabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static StaffUserResponse from(StaffUser u) {
        return new StaffUserResponse(
            u.getId(),
            u.getEmail(),
            u.getRole(),
            u.getCountry(),
            u.isActive(),
            u.isTwoFactorEnabled(),
            u.getCreatedAt(),
            u.getUpdatedAt()
        );
    }
}