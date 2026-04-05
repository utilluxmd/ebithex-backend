package com.ebithex.auth.security;

import com.ebithex.shared.security.CorrelationIdFilter;
import com.ebithex.shared.security.JwtAuthFilter;
import com.ebithex.shared.security.RateLimitFilter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    public static final String SUPPORT = "SUPPORT";
    public static final String COUNTRY_ADMIN = "COUNTRY_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String MERCHANT_KYC_VERIFIED = "MERCHANT_KYC_VERIFIED";
    public static final String MERCHANT = "MERCHANT";
    public static final String AGENT = "AGENT";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String FINANCE = "FINANCE";
    public static final String RECONCILIATION = "RECONCILIATION";
    public static final String COMPLIANCE = "COMPLIANCE";
    private final CorrelationIdFilter correlationIdFilter;
    private final ApiKeyAuthFilter    apiKeyAuthFilter;
    private final JwtAuthFilter       jwtAuthFilter;
    private final RateLimitFilter     rateLimitFilter;
    private final Environment         environment;

    @Value("${ebithex.security.cors.allowed-origins}")
    private List<String> allowedOrigins;

    /**
     * Vérifie au démarrage que CORS wildcard ({@code *}) n'est pas activé en production.
     * Un CORS wildcard en prod permettrait à n'importe quel site de faire des requêtes
     * cross-origin avec credentials, exposant les données des utilisateurs authentifiés.
     *
     * @throws IllegalStateException si le profil prod est actif et origins contient {@code *}
     */
    @PostConstruct
    void validateCorsNotWildcardInProduction() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd && allowedOrigins.stream().anyMatch("*"::equals)) {
            throw new IllegalStateException(
                "CONFIGURATION CRITIQUE : CORS wildcard (*) détecté en profil 'prod'. " +
                "Définir ebithex.security.cors.allowed-origins avec des origines explicites. " +
                "Exemple : https://dashboard.ebithex.io,https://app.ebithex.io");
        }
        if (isProd) {
            log.info("CORS origines autorisées en prod : {}", allowedOrigins);
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                .referrerPolicy(ref -> ref
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'none'; " +
                        "script-src 'self'; " +
                        "style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data: blob:; " +
                        "font-src 'self' data:; " +
                        "connect-src 'self'; " +
                        "form-action 'self'; " +
                        "frame-ancestors 'none'"))
                // Permissions-Policy via addHeaderWriter : évite l'API permissionsPolicy()
                // dépréciée depuis Spring Security 6.3 et supprimée en 7.0
                .addHeaderWriter(new StaticHeadersWriter(
                    "Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=(self), usb=(), fullscreen=(self)"))
            )
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh").permitAll()
                .requestMatchers("/v1/callbacks/**").permitAll()  // secured by HMAC signature
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/health/**").hasAnyRole(ADMIN, SUPER_ADMIN, FINANCE, SUPPORT)
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/error").permitAll()
                // Merchant endpoints (API Key or JWT)
                .requestMatchers("/v1/payments/**").hasAnyRole(MERCHANT, MERCHANT_KYC_VERIFIED, AGENT)
                .requestMatchers("/v1/wallet/withdrawals").hasRole(MERCHANT_KYC_VERIFIED)
                .requestMatchers("/v1/wallet/**").hasAnyRole(MERCHANT, MERCHANT_KYC_VERIFIED)
                .requestMatchers("/v1/payouts/bulk/**").hasRole(MERCHANT_KYC_VERIFIED)
                .requestMatchers("/v1/payouts/**").hasAnyRole(MERCHANT_KYC_VERIFIED, AGENT)
                .requestMatchers("/v1/webhooks/**").hasAnyRole(MERCHANT, MERCHANT_KYC_VERIFIED)
                .requestMatchers("/v1/merchants/kyc/documents/**").hasAnyRole(MERCHANT, MERCHANT_KYC_VERIFIED)
                .requestMatchers("/v1/merchants/**").hasAnyRole(MERCHANT, MERCHANT_KYC_VERIFIED)
                // Back-office auth (public)
                .requestMatchers("/internal/auth/login", "/internal/auth/login/verify-otp").permitAll()
                // Back-office endpoints
                .requestMatchers("/internal/staff-users/**").hasAnyRole(ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/merchants/**").hasAnyRole(COUNTRY_ADMIN, ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/reconciliation/export/**").hasAnyRole(RECONCILIATION, ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/reconciliation/**").hasAnyRole(RECONCILIATION, FINANCE, COUNTRY_ADMIN, ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/finance/**").hasAnyRole(FINANCE, ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/support/**").hasAnyRole(SUPPORT, COUNTRY_ADMIN, ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/webhooks/**").hasAnyRole(SUPPORT, ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/audit-logs/**").hasAnyRole(ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/config/**").hasAnyRole(ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/aml/**").hasAnyRole(COMPLIANCE,ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/sanctions/**").hasAnyRole(COMPLIANCE,ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/disputes/**").hasAnyRole(SUPPORT,COUNTRY_ADMIN,ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/settlement/**").hasAnyRole(FINANCE,ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/regulatory/**").hasAnyRole(FINANCE,ADMIN, SUPER_ADMIN)
                .requestMatchers("/internal/admin/**").hasRole(SUPER_ADMIN)
                // Merchant dispute endpoints
                .requestMatchers("/v1/disputes/**").hasAnyRole(MERCHANT, MERCHANT_KYC_VERIFIED)
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                .accessDeniedHandler((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
            .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, JwtAuthFilter.class)
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "X-API-Key", "Content-Type",
                                          "X-Ebithex-Signature", "X-Ebithex-Event"));
        config.setExposedHeaders(List.of(
            "X-Request-ID",
            "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset",
            "Retry-After"
        ));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}