package com.ebithex.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Filtre de rate limiting par marchand (API Key / JWT) ou par IP (endpoints publics).
 *
 * <p>Ordre dans la chaîne Spring Security :
 *   ApiKeyAuthFilter → JwtAuthFilter → RateLimitFilter → Controllers
 *
 * <p>Attribution du plan :
 * <ul>
 *   <li>MERCHANT_KYC_VERIFIED → PREMIUM  (300 req/min, 10 000 req/h)</li>
 *   <li>MERCHANT / AGENT      → STANDARD (60 req/min,   3 000 req/h)</li>
 *   <li>Non authentifié       → ANONYMOUS (10 req/min,    300 req/h) — par IP</li>
 * </ul>
 *
 * <p><b>Fail-safe :</b> si Redis est indisponible, {@link RateLimitService} bascule
 * automatiquement sur {@link LocalRateLimitFallback} (compteurs en mémoire par nœud).
 * Le rate limiting reste donc actif — ce n'est pas un fail-open.
 *
 * <p>Endpoints exclus : /v1/callbacks/**, /actuator/**, /v3/api-docs/**, /swagger-ui/**
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ObjectMapper     objectMapper;

    private static final String[] EXCLUDED_PREFIXES = {
        "/v1/callbacks/",
        "/actuator/",
        "/v3/api-docs",
        "/swagger-ui"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String        identifier;
        RateLimitPlan plan;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof EbithexPrincipal principal) {
            identifier = "merchant:" + principal.merchantId();
            plan = principal.hasRole(Role.MERCHANT_KYC_VERIFIED)
                ? RateLimitPlan.PREMIUM
                : RateLimitPlan.STANDARD;
        } else {
            identifier = "ip:" + resolveClientIp(request);
            plan = RateLimitPlan.ANONYMOUS;
        }

        // RateLimitService gère lui-même le fallback local si Redis est down.
        // Aucun fail-open ici : le rate limiting reste actif même sans Redis.
        RateLimitService.RateLimitResult result = rateLimitService.check(identifier, plan);

        response.setHeader("X-RateLimit-Limit",     String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        response.setHeader("X-RateLimit-Reset",     String.valueOf(result.resetAt()));

        if (!result.allowed()) {
            long retryAfter = Math.max(result.resetAt() - System.currentTimeMillis() / 1000, 1);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            log.warn("Rate limit dépassé — identifier={} plan={}", identifier, plan);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of(
                "status",  429,
                "error",   "Too Many Requests",
                "message", "Limite de requêtes dépassée. Réessayez dans " + retryAfter + "s."
            ));
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Retourne l'IP réelle du client.
     *
     * <p>L'IP est lue depuis {@code request.getRemoteAddr()} uniquement.
     * La résolution depuis {@code X-Forwarded-For} est assurée en amont par le
     * {@code RemoteIpValve} de Tomcat ({@code server.forward-headers-strategy=NATIVE}),
     * qui valide que la requête provient d'un proxy de confiance avant de mettre à jour
     * {@code remoteAddr}. Lire {@code X-Forwarded-For} ici permettrait à n'importe quel
     * client de forger son IP et de contourner le rate limiting par IP.
     */
    private String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}