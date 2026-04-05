package com.ebithex.shared.security;

import com.ebithex.shared.apikey.ApiKeyScope;
import org.springframework.stereotype.Component;

/**
 * Vérifie qu'une requête authentifiée par clé API possède le scope requis.
 *
 * <p>Les requêtes authentifiées par JWT (staff back-office ou merchant dashboard)
 * ne sont pas soumises à restriction de scope — leur périmètre est géré par les
 * rôles Spring Security ({@code ROLE_ADMIN}, {@code ROLE_MERCHANT_KYC_VERIFIED}, etc.).
 *
 * <p>Usage dans les contrôleurs :
 * <pre>
 * {@code @PreAuthorize("@scopeGuard.hasScope(authentication.principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_WRITE)")}
 * </pre>
 *
 * <p>Règles :
 * <ul>
 *   <li>JWT (apiKeyId == null) → toujours autorisé</li>
 *   <li>Clé avec {@code FULL_ACCESS} → toujours autorisé</li>
 *   <li>Sinon → le scope exact doit être présent</li>
 * </ul>
 */
@Component("scopeGuard")
public class ScopeGuard {

    public boolean hasScope(EbithexPrincipal principal, ApiKeyScope required) {
        if (principal == null) return false;
        // Requête JWT — pas de restriction de scope
        if (principal.apiKeyId() == null) return true;
        if (principal.scopes().contains(ApiKeyScope.FULL_ACCESS)) return true;
        return principal.scopes().contains(required);
    }
}
