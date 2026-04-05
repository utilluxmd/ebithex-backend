package com.ebithex.shared.security;

import com.ebithex.shared.apikey.ApiKeyScope;
import lombok.Builder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Unified principal for all roles (merchants, agents, back-office users).
 * Injected via @AuthenticationPrincipal in controllers.
 * Built by ApiKeyAuthFilter after successful authentication.
 */
@Builder
public record EbithexPrincipal(
    UUID id,
    String email,
    Set<Role> roles,
    boolean active,
    UUID merchantId,   // non-null for MERCHANT, MERCHANT_KYC_VERIFIED, AGENT
    UUID agencyId,     // non-null for AGENT only
    String country,    // non-null for COUNTRY_ADMIN only
    boolean testMode,  // true si la requête est authentifiée avec une clé ap_test_
    UUID apiKeyId,     // non-null si authentifié par clé API (null = JWT)
    Set<ApiKeyScope> scopes  // scopes de la clé API (vide si JWT)
) implements UserDetails {

    /** True if principal is scoped to a specific merchant */
    public boolean hasMerchantScope() { return merchantId != null; }

    /** True if principal is scoped to a specific agency (AGENT only) */
    public boolean hasAgencyScope() { return agencyId != null; }

    /** True if principal is scoped to a specific country (COUNTRY_ADMIN only) */
    public boolean hasCountryScope() {
        return country != null && roles.contains(Role.COUNTRY_ADMIN);
    }

    public boolean hasRole(Role role) { return roles.contains(role); }

    /** True si cette requête est authentifiée par clé API (vs JWT). */
    public boolean isApiKeyAuth() { return apiKeyId != null; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
            .toList();
    }

    @Override public String getPassword()              { return ""; }
    @Override public String getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return active; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return active; }
}
