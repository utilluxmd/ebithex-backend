package com.ebithex.auth;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.domain.StaffUser;
import com.ebithex.merchant.infrastructure.StaffUserRepository;
import com.ebithex.shared.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — 2FA email OTP pour les opérateurs back-office.
 *
 * Couvre :
 *  - Login avec 2FA activé → tempToken (pas de JWT direct)
 *  - Verify-OTP valide → JWT final émis
 *  - Verify-OTP code invalide → 400 OTP_INVALID
 *  - Verify-OTP tempToken expiré/invalide → 400 OTP_EXPIRED
 *  - Login avec 2FA désactivé → JWT direct (pas de 2FA)
 *  - Vérification que l'OTP est stocké dans Redis
 */
@DisplayName("2FA — Email OTP pour opérateurs back-office")
class TwoFactorAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory     factory;
    @Autowired private StaffUserRepository staffUserRepository;
    @Autowired private PasswordEncoder     passwordEncoder;
    @Autowired private StringRedisTemplate redis;

    private static final String PASSWORD = "Admin@1234!";

    @BeforeEach
    void setUp() {
        // mailSender mocké dans AbstractIntegrationTest — pas d'emails réels
    }

    @Test
    @DisplayName("Login avec 2FA activé → requiresTwoFactor=true + tempToken, pas de JWT")
    void login_with2faEnabled_returnsTempToken() {
        StaffUser operator = createStaffUser(true);

        ResponseEntity<Map> resp = login(operator.getEmail(), PASSWORD);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("requiresTwoFactor")).isEqualTo("true");
        assertThat(data.get("tempToken")).isNotNull();
        assertThat(data).doesNotContainKey("accessToken");
    }

    @Test
    @DisplayName("Login → verify-otp valide → JWT final émis")
    void verifyOtp_validCode_returnsJwt() {
        StaffUser operator = createStaffUser(true);

        // Étape 1 : login
        ResponseEntity<Map> loginResp = login(operator.getEmail(), PASSWORD);
        String tempToken = (String) ((Map<?, ?>) loginResp.getBody().get("data")).get("tempToken");

        // Récupérer l'OTP depuis Redis (clé : otp:code:{email})
        String otp = redis.opsForValue().get("otp:code:" + operator.getEmail());
        assertThat(otp).isNotNull().hasSize(6).matches("\\d{6}");

        // Étape 2 : verify-otp
        ResponseEntity<Map> verifyResp = verifyOtp(tempToken, otp);

        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) verifyResp.getBody().get("data");
        assertThat(data.get("accessToken")).isNotNull();
        assertThat(data.get("role")).isEqualTo(Role.ADMIN.name());

        // Clés Redis purgées après usage
        assertThat(redis.opsForValue().get("otp:code:" + operator.getEmail())).isNull();
    }

    @Test
    @DisplayName("Verify-OTP code incorrect → 400 OTP_INVALID")
    void verifyOtp_wrongCode_returns400() {
        StaffUser operator = createStaffUser(true);
        ResponseEntity<Map> loginResp = login(operator.getEmail(), PASSWORD);
        String tempToken = (String) ((Map<?, ?>) loginResp.getBody().get("data")).get("tempToken");

        ResponseEntity<Map> resp = verifyOtp(tempToken, "000000");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("OTP_INVALID");
    }

    @Test
    @DisplayName("Verify-OTP tempToken invalide → 400 OTP_EXPIRED")
    void verifyOtp_invalidTempToken_returns400() {
        StaffUser operator = createStaffUser(true);
        login(operator.getEmail(), PASSWORD);
        String otp = redis.opsForValue().get("otp:code:" + operator.getEmail());

        ResponseEntity<Map> resp = verifyOtp("invalid-token-xyz", otp);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("errorCode")).isEqualTo("OTP_EXPIRED");
    }

    @Test
    @DisplayName("Login avec 2FA désactivé → JWT direct")
    void login_with2faDisabled_returnsJwtDirectly() {
        StaffUser operator = createStaffUser(false);

        ResponseEntity<Map> resp = login(operator.getEmail(), PASSWORD);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("accessToken")).isNotNull();
        assertThat(data).doesNotContainKey("requiresTwoFactor");
    }

    @Test
    @DisplayName("OTP valide ne peut être utilisé qu'une seule fois")
    void verifyOtp_reuse_returns400() {
        StaffUser operator = createStaffUser(true);
        ResponseEntity<Map> loginResp = login(operator.getEmail(), PASSWORD);
        String tempToken = (String) ((Map<?, ?>) loginResp.getBody().get("data")).get("tempToken");
        String otp = redis.opsForValue().get("otp:code:" + operator.getEmail());

        // Premier usage → OK
        assertThat(verifyOtp(tempToken, otp).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Deuxième usage → KO (clés Redis purgées)
        ResponseEntity<Map> resp2 = verifyOtp(tempToken, otp);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private StaffUser createStaffUser(boolean twoFactorEnabled) {
        // ADMIN/SUPER_ADMIN force 2FA regardless of the flag — use SUPPORT when testing 2FA disabled
        Role role = twoFactorEnabled ? Role.ADMIN : Role.SUPPORT;
        StaffUser user = StaffUser.builder()
            .email("staff-2fa-" + System.nanoTime() + "@ebithex.io")
            .hashedPassword(passwordEncoder.encode(PASSWORD))
            .role(role)
            .active(true)
            .twoFactorEnabled(twoFactorEnabled)
            .build();
        return staffUserRepository.save(user);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return restTemplate.postForEntity(
            url("/internal/auth/login"),
            Map.of("email", email, "password", password),
            Map.class);
    }

    private ResponseEntity<Map> verifyOtp(String tempToken, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
            url("/internal/auth/login/verify-otp"), HttpMethod.POST,
            new HttpEntity<>(Map.of("tempToken", tempToken, "code", code), headers),
            Map.class);
    }
}