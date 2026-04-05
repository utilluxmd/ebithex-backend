package com.ebithex.admin;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.shared.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Authentification opérateurs back-office.
 *
 * Couvre :
 *  - Login valide (ADMIN, COUNTRY_ADMIN)
 *  - Login mot de passe incorrect → 400
 *  - Login email inconnu → 400
 *  - Token opérateur donne accès aux endpoints /internal
 *  - Token opérateur n'a PAS accès aux endpoints /v1/payments (merchant-only)
 *  - Logout révoque le token
 *  - Brute-force : 5 échecs → 6ème tentative bloquée (LOGIN_ATTEMPTS_EXCEEDED)
 */
@DisplayName("Admin Auth — Login opérateur, Token, Brute-force")
class AdminAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Login ADMIN valide → 200, 2FA required then accessToken after OTP")
    void login_validAdmin_returnsToken() {
        TestDataFactory.StaffUserCredentials op = factory.createStaffUser(Role.ADMIN);

        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", op.email(), "password", op.password()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        // ADMIN forces 2FA — login returns tempToken, not accessToken directly
        assertThat(data.get("requiresTwoFactor")).isEqualTo("true");
        assertThat(data.get("tempToken")).isNotNull();

        // Complete 2FA to get actual JWT
        String token = completeOtpFlow((String) data.get("tempToken"), op.email());
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("Login COUNTRY_ADMIN valide → token obtenu (direct, pas de 2FA obligatoire)")
    void login_countryAdmin_tokenIsValid() {
        TestDataFactory.StaffUserCredentials op = factory.createStaffUser(Role.COUNTRY_ADMIN, "CI");

        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", op.email(), "password", op.password()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        String token = (String) data.get("accessToken");
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("Login mot de passe incorrect → 400 INVALID_CREDENTIALS")
    void login_wrongPassword_returns400() {
        TestDataFactory.StaffUserCredentials op = factory.createStaffUser(Role.ADMIN);

        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", op.email(), "password", "WrongPass123!"),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("Login email inconnu → 400 INVALID_CREDENTIALS")
    void login_unknownEmail_returns400() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", "nobody@ebithex.io", "password", "Admin@1234!"),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("INVALID_CREDENTIALS");
    }

    // ── Accès aux endpoints ───────────────────────────────────────────────────

    @Test
    @DisplayName("Token ADMIN donne accès à /internal/merchants")
    void token_admin_accessesBackOffice() {
        TestDataFactory.StaffUserCredentials op = factory.createStaffUser(Role.ADMIN);
        String token = loginOperator(op);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/merchants"), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Token ADMIN ne peut PAS accéder à /v1/payments (merchant-only) → 403")
    void token_admin_cannotAccessMerchantEndpoints() {
        TestDataFactory.StaffUserCredentials op = factory.createStaffUser(Role.ADMIN);
        String token = loginOperator(op);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Logout révoque le token — requête suivante → 401")
    void logout_revokesToken() {
        TestDataFactory.StaffUserCredentials op = factory.createStaffUser(Role.ADMIN);
        String token = loginOperator(op);

        // Vérifier que le token fonctionne avant logout
        ResponseEntity<Map> before = restTemplate.exchange(
            url("/internal/merchants"), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Logout
        restTemplate.exchange(
            url("/internal/auth/logout"), HttpMethod.POST,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        // Token doit être révoqué
        ResponseEntity<Map> after = restTemplate.exchange(
            url("/internal/merchants"), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Brute-force ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("5 échecs consécutifs → 6ème tentative bloquée (LOGIN_ATTEMPTS_EXCEEDED)")
    void bruteForce_5failures_accountLocked() {
        TestDataFactory.StaffUserCredentials op = factory.createStaffUser(Role.SUPPORT);

        // 5 tentatives échouées
        for (int i = 0; i < 5; i++) {
            restTemplate.postForEntity(
                url("/internal/auth/login"),
                Map.of("email", op.email(), "password", "WrongPass!"),
                Map.class);
        }

        // 6ème tentative — même avec le bon mot de passe, le compte est verrouillé
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", op.email(), "password", op.password()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("LOGIN_ATTEMPTS_EXCEEDED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String loginOperator(TestDataFactory.StaffUserCredentials op) {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", op.email(), "password", op.password()),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");

        // 2FA mandatory for ADMIN/SUPER_ADMIN — complete OTP flow
        if ("true".equals(data.get("requiresTwoFactor"))) {
            return completeOtpFlow((String) data.get("tempToken"), op.email());
        }
        return (String) data.get("accessToken");
    }

    private String completeOtpFlow(String tempToken, String email) {
        String otp = redisTemplate.opsForValue().get("otp:code:" + email);
        assertThat(otp).as("OTP should be in Redis").isNotNull();

        ResponseEntity<Map> verifyResp = restTemplate.exchange(
            url("/internal/auth/login/verify-otp"), HttpMethod.POST,
            new HttpEntity<>(Map.of("tempToken", tempToken, "code", otp)),
            Map.class);
        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) ((Map<?, ?>) verifyResp.getBody().get("data")).get("accessToken");
    }
}