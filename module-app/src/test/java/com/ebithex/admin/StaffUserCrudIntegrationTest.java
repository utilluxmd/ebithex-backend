package com.ebithex.admin;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.shared.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — CRUD des utilisateurs back-office (StaffUser).
 *
 * Couvre :
 *  - SUPER_ADMIN peut créer un utilisateur → 201
 *  - SUPER_ADMIN peut lire la liste → 200
 *  - SUPER_ADMIN peut modifier le rôle → 200
 *  - SUPER_ADMIN peut désactiver → 200
 *  - SUPER_ADMIN peut reset le mot de passe → 200 + temporaryPassword
 *  - ADMIN ne peut pas créer → 403
 *  - SUPER_ADMIN ne peut pas désactiver son propre compte → 400
 *  - Email déjà utilisé → 400
 *  - COUNTRY_ADMIN sans pays → 400
 */
@DisplayName("StaffUser CRUD — Gestion des utilisateurs back-office")
class StaffUserCrudIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    // ── Création ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPER_ADMIN crée un utilisateur SUPPORT → 201")
    void create_superAdmin_returns201() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("SUPPORT", null), factory.bearerHeaders(token)),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("role")).isEqualTo("SUPPORT");
        assertThat(data.get("active")).isEqualTo(true);
        assertThat(data.get("id")).isNotNull();
    }

    @Test
    @DisplayName("SUPER_ADMIN crée un COUNTRY_ADMIN avec pays → 201")
    void create_countryAdmin_withCountry_returns201() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("COUNTRY_ADMIN", "CI"), factory.bearerHeaders(token)),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("country")).isEqualTo("CI");
    }

    @Test
    @DisplayName("COUNTRY_ADMIN sans pays → 400 COUNTRY_REQUIRED")
    void create_countryAdmin_withoutCountry_returns400() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("COUNTRY_ADMIN", null), factory.bearerHeaders(token)),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("COUNTRY_REQUIRED");
    }

    @Test
    @DisplayName("Email déjà utilisé → 400 EMAIL_ALREADY_EXISTS")
    void create_duplicateEmail_returns400() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        Map<String, Object> body = Map.of(
            "email", superAdmin.email(),  // email déjà pris
            "role", "SUPPORT",
            "password", "Admin@1234!",
            "twoFactorEnabled", false
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(body, factory.bearerHeaders(token)),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("ADMIN ne peut pas créer → 403")
    void create_admin_returns403() {
        TestDataFactory.StaffUserCredentials admin = factory.createStaffUser(Role.ADMIN);
        String token = loginStaffUser(admin);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("SUPPORT", null), factory.bearerHeaders(token)),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN peut lister les utilisateurs → 200")
    void list_admin_returns200() {
        TestDataFactory.StaffUserCredentials admin = factory.createStaffUser(Role.ADMIN);
        String token = loginStaffUser(admin);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users?page=0&size=10"), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("SUPER_ADMIN peut récupérer un utilisateur par ID → 200")
    void getById_superAdmin_returns200() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        // Créer un utilisateur
        ResponseEntity<Map> created = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("FINANCE", null), factory.bearerHeaders(token)), Map.class);
        String userId = (String) ((Map<?, ?>) created.getBody().get("data")).get("id");

        // Le récupérer par ID
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users/" + userId), HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("role")).isEqualTo("FINANCE");
    }

    // ── Modification ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPER_ADMIN peut modifier le rôle d'un utilisateur → 200")
    void update_role_returns200() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        // Créer
        ResponseEntity<Map> created = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("SUPPORT", null), factory.bearerHeaders(token)), Map.class);
        String userId = (String) ((Map<?, ?>) created.getBody().get("data")).get("id");

        // Modifier
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users/" + userId), HttpMethod.PUT,
            new HttpEntity<>(Map.of("role", "RECONCILIATION"), factory.bearerHeaders(token)),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("role")).isEqualTo("RECONCILIATION");
    }

    // ── Désactivation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPER_ADMIN peut désactiver un autre utilisateur → 200")
    void deactivate_otherUser_returns200() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        ResponseEntity<Map> created = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("SUPPORT", null), factory.bearerHeaders(token)), Map.class);
        String userId = (String) ((Map<?, ?>) created.getBody().get("data")).get("id");

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users/" + userId), HttpMethod.DELETE,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("SUPER_ADMIN ne peut pas se désactiver lui-même → 400 CANNOT_DEACTIVATE_SELF")
    void deactivate_self_returns400() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);
        String myId = superAdmin.staffUserId().toString();

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users/" + myId), HttpMethod.DELETE,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("CANNOT_DEACTIVATE_SELF");
    }

    // ── Reset mot de passe ────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPER_ADMIN peut réinitialiser le mot de passe → 200 + temporaryPassword")
    void resetPassword_returns200_withTempPassword() {
        TestDataFactory.StaffUserCredentials superAdmin = factory.createStaffUser(Role.SUPER_ADMIN);
        String token = loginStaffUser(superAdmin);

        ResponseEntity<Map> created = restTemplate.exchange(
            url("/internal/staff-users"), HttpMethod.POST,
            new HttpEntity<>(newUserBody("FINANCE", null), factory.bearerHeaders(token)), Map.class);
        String userId = (String) ((Map<?, ?>) created.getBody().get("data")).get("id");

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/internal/staff-users/" + userId + "/reset-password"), HttpMethod.POST,
            new HttpEntity<>(factory.bearerHeaders(token)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("temporaryPassword")).isNotNull().isInstanceOf(String.class);
        assertThat((String) data.get("temporaryPassword")).hasSizeGreaterThanOrEqualTo(10);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String loginStaffUser(TestDataFactory.StaffUserCredentials creds) {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", creds.email(), "password", creds.password()),
            Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");

        // 2FA mandatory for ADMIN/SUPER_ADMIN — complete OTP flow via Redis
        if ("true".equals(data.get("requiresTwoFactor"))) {
            String tempToken = (String) data.get("tempToken");
            String otp = redisTemplate.opsForValue().get("otp:code:" + creds.email());
            assertThat(otp).as("OTP should be stored in Redis").isNotNull();

            ResponseEntity<Map> verifyResp = restTemplate.exchange(
                url("/internal/auth/login/verify-otp"), HttpMethod.POST,
                new HttpEntity<>(Map.of("tempToken", tempToken, "code", otp)),
                Map.class);
            assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            return (String) ((Map<?, ?>) verifyResp.getBody().get("data")).get("accessToken");
        }

        return (String) data.get("accessToken");
    }

    private Map<String, Object> newUserBody(String role, String country) {
        var body = new java.util.HashMap<String, Object>();
        body.put("email", "new-staff-" + UUID.randomUUID() + "@ebithex.io");
        body.put("role", role);
        body.put("password", "Admin@1234!XYZ");
        body.put("twoFactorEnabled", false);
        if (country != null) body.put("country", country);
        return body;
    }
}