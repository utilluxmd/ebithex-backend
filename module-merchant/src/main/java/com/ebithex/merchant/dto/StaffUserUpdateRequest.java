package com.ebithex.merchant.dto;

import com.ebithex.shared.security.Role;

/**
 * Payload de mise à jour partielle d'un utilisateur back-office.
 * Seuls les champs non-null sont appliqués.
 *
 * @param role             Nouveau rôle (null = inchangé)
 * @param country          Nouveau pays ISO-3166-1 alpha-2 (null = inchangé)
 * @param active           Activer/désactiver le compte (null = inchangé)
 * @param twoFactorEnabled Modifier le statut 2FA (null = inchangé)
 */
public record StaffUserUpdateRequest(
    Role    role,
    String  country,
    Boolean active,
    Boolean twoFactorEnabled
) {}