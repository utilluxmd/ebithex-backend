package com.ebithex.auth.security;

import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.domain.ApiKey;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.infrastructure.ApiKeyRepository;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.shared.apikey.ApiKeyType;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.shared.security.CorrelationIdFilter;
import com.ebithex.shared.security.JwtService;
import com.ebithex.shared.security.Role;
import com.ebithex.shared.security.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gère l'authentification par clé API (X-API-Key) et par JWT Bearer.
 *
 * <p>Pour les clés API, contrôle successivement :
 * <ol>
 *   <li>Hash : la clé (courante ou précédente dans la grace period) est connue</li>
 *   <li>Expiration : la clé n'est pas expirée</li>
 *   <li>IP : l'IP cliente est dans la whitelist (si configurée)</li>
 *   <li>Marchand actif : le compte n'est pas désactivé</li>
 * </ol>
 *
 * <p>Les scopes de la clé sont portés dans {@link EbithexPrincipal#scopes()} et
 * vérifiés par {@link com.ebithex.shared.security.ScopeGuard} au niveau des contrôleurs
 * via {@code @PreAuthorize("@scopeGuard.hasScope(...)")}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyRepository      apiKeyRepository;
    private final MerchantRepository    merchantRepository;
    private final ApiKeyService         apiKeyService;
    private final JwtService            jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        try {
            String apiKey = request.getHeader(API_KEY_HEADER);
            if (apiKey != null && !apiKey.isBlank()) {
                handleApiKey(apiKey, request, response);
            } else {
                handleJwt(request);
            }
            if (!response.isCommitted()) {
                chain.doFilter(request, response);
            }
        } finally {
            SandboxContextHolder.clear();
        }
    }

    // ── Authentification par clé API ──────────────────────────────────────────

    private void handleApiKey(String rawKey, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        String hash = ApiKeyService.sha256(rawKey);
        LocalDateTime now = LocalDateTime.now();

        ApiKey key = apiKeyRepository.findByHash(hash, now).orElse(null);
        if (key == null) return; // clé inconnue — laisse Spring Security rejeter

        // 1. Expiration
        if (key.isExpired()) {
            log.warn("Clé API expirée: keyId={}", key.getId());
            return;
        }

        // 2. Restriction IP
        String clientIp = resolveClientIp(request);
        if (!key.isIpAllowed(clientIp)) {
            log.warn("IP non autorisée pour keyId={}: ip={}", key.getId(), clientIp);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "IP not allowed");
            return;
        }

        // 3. Marchand actif
        Merchant merchant = merchantRepository.findById(key.getMerchantId()).orElse(null);
        if (merchant == null || !merchant.isActive()) return;

        // 4. Contexte sandbox
        boolean isSandbox = key.getType() == ApiKeyType.TEST || merchant.isTestMode();
        SandboxContextHolder.set(isSandbox);

        // 5. Authentification
        EbithexPrincipal principal = buildPrincipal(merchant, key);
        setAuth(principal);

        // 6. Mise à jour lastUsedAt (async, non bloquant)
        apiKeyService.touchLastUsed(key.getId());
    }

    // ── Authentification par JWT ───────────────────────────────────────────────

    private void handleJwt(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return;

        String token = authHeader.substring(7);
        if (!jwtService.validateToken(token)) return;
        if (tokenBlacklistService.isRevoked(jwtService.getJti(token))) return;

        // Rejeter les refresh tokens utilisés comme access tokens.
        // Un refresh token (type="refresh", TTL 7 jours) ne doit accéder à aucune ressource :
        // seuls "access" (TTL 15 min) et "operator" sont autorisés pour les appels API.
        String tokenType = jwtService.extractTokenType(token);
        if (!"access".equals(tokenType) && !"operator".equals(tokenType)) {
            log.warn("Tentative d'utilisation d'un token de type '{}' comme access token — rejeté", tokenType);
            return;
        }

        // JWT = staff / merchant dashboard — toujours schéma prod
        SandboxContextHolder.set(false);

        if ("operator".equals(tokenType)) {
            UUID operatorId    = jwtService.extractMerchantId(token);
            String email       = jwtService.extractEmail(token);
            String country     = jwtService.extractCountry(token);
            Set<Role> roles    = jwtService.extractRoles(token).stream()
                .map(Role::valueOf).collect(Collectors.toSet());
            EbithexPrincipal principal = EbithexPrincipal.builder()
                .id(operatorId).email(email).roles(roles)
                .active(true).country(country)
                .scopes(Set.of()).build();
            setAuth(principal);
        } else {
            merchantRepository.findById(jwtService.extractMerchantId(token))
                .filter(Merchant::isActive)
                .ifPresent(m -> setAuth(buildJwtMerchantPrincipal(m)));
        }
    }

    // ── Builders de principal ─────────────────────────────────────────────────

    private EbithexPrincipal buildPrincipal(Merchant merchant, ApiKey key) {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.MERCHANT);
        if (merchant.isKycVerified()) roles.add(Role.MERCHANT_KYC_VERIFIED);

        boolean isSandbox = key.getType() == ApiKeyType.TEST || merchant.isTestMode();
        return EbithexPrincipal.builder()
            .id(merchant.getId())
            .email(merchant.getEmail())
            .roles(roles)
            .active(merchant.isActive())
            .merchantId(merchant.getId())
            .testMode(isSandbox)
            .apiKeyId(key.getId())
            .scopes(key.parsedScopes())
            .build();
    }

    private EbithexPrincipal buildJwtMerchantPrincipal(Merchant merchant) {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.MERCHANT);
        if (merchant.isKycVerified()) roles.add(Role.MERCHANT_KYC_VERIFIED);
        return EbithexPrincipal.builder()
            .id(merchant.getId())
            .email(merchant.getEmail())
            .roles(roles)
            .active(merchant.isActive())
            .merchantId(merchant.getId())
            .testMode(false)
            .scopes(Set.of())   // JWT — pas de restriction de scope
            .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setAuth(EbithexPrincipal principal) {
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        if (principal.merchantId() != null) {
            MDC.put(CorrelationIdFilter.MERCHANT_MDC_KEY, principal.merchantId().toString());
        }
    }

    /**
     * Retourne l'IP réelle du client.
     *
     * <p>NE pas lire {@code X-Forwarded-For} directement : ce header est forgeable par
     * n'importe quel client. L'IP réelle est résolue en amont par le {@code RemoteIpValve}
     * de Tomcat (activé via {@code server.forward-headers-strategy=NATIVE}) qui valide
     * que la requête provient d'un proxy de confiance avant de mettre à jour
     * {@code request.getRemoteAddr()}.
     */
    private String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
