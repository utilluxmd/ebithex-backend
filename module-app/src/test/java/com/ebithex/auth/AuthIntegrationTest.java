package com.ebithex.auth;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Authentification (register, login, logout, refresh token).
 */
@DisplayName("Auth — Register / Login / Logout / Refresh")
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    // ── Register ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Register → 200, retourne apiKey + accessToken + refreshToken")
    void register_success_returnsCredentials() {
        Map<String, String> body = Map.of(
            "businessName", "Bakery Abidjan",
            "email",        factory.uniqueEmail(),
            "password",     "Test@1234!",
            "country",      "CI"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
            url("/v1/auth/register"), body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("liveApiKey")).asString().startsWith("ap_live_");
        assertThat(data.get("testApiKey")).asString().startsWith("ap_test_");
        assertThat(data.get("accessToken")).isNotNull();
        assertThat(data.get("refreshToken")).isNotNull();
        assertThat(data.get("merchantId")).isNotNull();
    }

    @Test
    @DisplayName("Register avec email existant → 400 DUPLICATE_EMAIL")
    void register_duplicateEmail_returns400() {
        String email = factory.uniqueEmail();
        Map<String, String> body = Map.of(
            "businessName", "Shop A", "email", email, "password", "Test@1234!", "country", "CI");
        restTemplate.postForEntity(url("/v1/auth/register"), body, Map.class);

        // Deuxième tentative avec le même email
        ResponseEntity<Map> response = restTemplate.postForEntity(
            url("/v1/auth/register"), body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("errorCode")).isEqualTo("DUPLICATE_EMAIL");
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Login valide → 200, nouveaux tokens")
    void login_validCredentials_returnsTokens() {
        TestDataFactory.MerchantCredentials creds = factory.registerMerchant(restTemplate, url(""));

        Map<String, String> loginBody = Map.of(
            "email",    creds.email(),
            "password", creds.password()
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
            url("/v1/auth/login"), loginBody, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accessToken")).isNotNull();
        assertThat(data.get("refreshToken")).isNotNull();
    }

    @Test
    @DisplayName("Login avec mauvais mot de passe → 400 INVALID_CREDENTIALS")
    void login_wrongPassword_returns400() {
        TestDataFactory.MerchantCredentials creds = factory.registerMerchant(restTemplate, url(""));

        Map<String, String> loginBody = Map.of(
            "email", creds.email(), "password", "WrongPassword!");
        ResponseEntity<Map> response = restTemplate.postForEntity(
            url("/v1/auth/login"), loginBody, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_CREDENTIALS");
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Logout → access token révoqué, requête suivante → 401")
    void logout_revokesAccessToken() {
        TestDataFactory.MerchantCredentials creds = factory.registerMerchant(restTemplate, url(""));

        // Vérifier que l'access token fonctionne avant logout
        HttpHeaders authHeaders = factory.bearerHeaders(creds.accessToken());
        ResponseEntity<Map> before = restTemplate.exchange(
            url("/v1/wallet"), HttpMethod.GET,
            new HttpEntity<>(authHeaders), Map.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Logout
        HttpHeaders logoutHeaders = factory.bearerHeaders(creds.accessToken());
        logoutHeaders.set("X-Refresh-Token", creds.refreshToken());
        restTemplate.exchange(
            url("/v1/auth/logout"), HttpMethod.POST,
            new HttpEntity<>(logoutHeaders), Map.class);

        // Vérifier que l'access token est maintenant refusé
        ResponseEntity<Map> after = restTemplate.exchange(
            url("/v1/wallet"), HttpMethod.GET,
            new HttpEntity<>(authHeaders), Map.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Refresh → nouvelle paire de tokens émise")
    void refresh_validToken_returnsNewPair() {
        TestDataFactory.MerchantCredentials creds = factory.registerMerchant(restTemplate, url(""));

        Map<String, String> body = Map.of("refreshToken", creds.refreshToken());
        ResponseEntity<Map> response = restTemplate.postForEntity(
            url("/v1/auth/refresh"), body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accessToken")).isNotNull();
        assertThat(data.get("refreshToken")).isNotNull();
        // Les nouveaux tokens doivent être différents des anciens
        assertThat(data.get("accessToken")).isNotEqualTo(creds.accessToken());
        assertThat(data.get("refreshToken")).isNotEqualTo(creds.refreshToken());
    }

    @Test
    @DisplayName("Refresh avec un token déjà utilisé → 400 (rotation)")
    void refresh_usedToken_returns400() {
        TestDataFactory.MerchantCredentials creds = factory.registerMerchant(restTemplate, url(""));
        Map<String, String> body = Map.of("refreshToken", creds.refreshToken());

        // Première utilisation — OK
        restTemplate.postForEntity(url("/v1/auth/refresh"), body, Map.class);

        // Deuxième utilisation du même refresh token — doit échouer (rotation)
        ResponseEntity<Map> response = restTemplate.postForEntity(
            url("/v1/auth/refresh"), body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("Refresh avec un access token (mauvais type) → 400")
    void refresh_withAccessToken_returns400() {
        TestDataFactory.MerchantCredentials creds = factory.registerMerchant(restTemplate, url(""));

        Map<String, String> body = Map.of("refreshToken", creds.accessToken()); // mauvais type
        ResponseEntity<Map> response = restTemplate.postForEntity(
            url("/v1/auth/refresh"), body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_TOKEN");
    }
}