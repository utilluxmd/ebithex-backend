package com.ebithex.merchant;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Conformité RGPD.
 *
 * Couvre :
 *  - Art. 15 : Export des données personnelles (GET /v1/merchants/gdpr/export)
 *  - Art. 17 : Droit à l'effacement — anonymisation (DELETE /v1/merchants/gdpr/data)
 *
 * Garanties testées :
 *  - L'export retourne les données du compte connecté uniquement
 *  - L'anonymisation désactive le compte et change l'email
 *  - Après anonymisation, le login avec les anciennes credentials échoue
 *  - Un autre marchand ne peut pas accéder à l'export d'autrui
 */
@DisplayName("RGPD — Export (art. 15) et Anonymisation (art. 17)")
class GdprIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerMerchant(restTemplate, url(""));
    }

    // ── Art. 15 — Droit d'accès ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /gdpr/export authentifié → 200 avec données du compte")
    void exportGdprData_authenticated_returns200WithAccountData() {
        HttpHeaders headers = factory.bearerHeaders(merchant.accessToken());

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/merchants/gdpr/export"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data).isNotNull();

        // Section "account" contient les données identifiantes
        Map<?, ?> account = (Map<?, ?>) data.get("account");
        assertThat(account).isNotNull();
        assertThat(account.get("merchantId")).isEqualTo(merchant.merchantId().toString());
        assertThat(account.get("email")).isEqualTo(merchant.email());
        assertThat(account.get("active")).isEqualTo(true);

        // Section "gdpr" contient les métadonnées de l'export
        Map<?, ?> gdpr = (Map<?, ?>) data.get("gdpr");
        assertThat(gdpr).isNotNull();
        assertThat(gdpr.get("dataController")).isEqualTo("Ebithex SAS");
        assertThat(gdpr.get("contact")).isEqualTo("dpo@ebithex.io");
        assertThat(gdpr.get("exportedAt")).isNotNull();
    }

    @Test
    @DisplayName("GET /gdpr/export sans authentification → 401")
    void exportGdprData_unauthenticated_returns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/merchants/gdpr/export"),
            HttpMethod.GET,
            HttpEntity.EMPTY,
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /gdpr/export avec clé API → 200 (clé avec scope FULL_ACCESS acceptée)")
    void exportGdprData_withApiKey_returns200() {
        HttpHeaders headers = factory.apiKeyHeaders(merchant.apiKey());

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/merchants/gdpr/export"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> account = (Map<?, ?>) ((Map<?, ?>) response.getBody().get("data")).get("account");
        assertThat(account.get("merchantId")).isEqualTo(merchant.merchantId().toString());
    }

    // ── Art. 17 — Droit à l'effacement ───────────────────────────────────────

    @Test
    @DisplayName("DELETE /gdpr/data → 200, compte anonymisé et désactivé")
    void anonymizeGdprData_authenticated_anonymizesAccount() {
        HttpHeaders headers = factory.bearerHeaders(merchant.accessToken());

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/merchants/gdpr/data"),
            HttpMethod.DELETE,
            new HttpEntity<>(headers),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Vérifier en base que l'email est anonymisé et le compte désactivé
        String emailInDb = jdbcTemplate.queryForObject(
            "SELECT email FROM merchants WHERE id = ?",
            String.class,
            merchant.merchantId()
        );
        assertThat(emailInDb).startsWith("deleted+").endsWith("@ebithex.invalid");

        String businessNameInDb = jdbcTemplate.queryForObject(
            "SELECT business_name FROM merchants WHERE id = ?",
            String.class,
            merchant.merchantId()
        );
        assertThat(businessNameInDb).isEqualTo("[SUPPRIMÉ]");

        Boolean activeInDb = jdbcTemplate.queryForObject(
            "SELECT active FROM merchants WHERE id = ?",
            Boolean.class,
            merchant.merchantId()
        );
        assertThat(activeInDb).isFalse();
    }

    @Test
    @DisplayName("Après anonymisation, login avec les anciennes credentials échoue (ACCOUNT_DISABLED)")
    void anonymizeGdprData_afterAnonymization_loginFails() {
        // Anonymiser
        restTemplate.exchange(
            url("/v1/merchants/gdpr/data"),
            HttpMethod.DELETE,
            new HttpEntity<>(factory.bearerHeaders(merchant.accessToken())),
            Map.class
        );

        // Tenter de se reconnecter avec les anciennes credentials
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
            url("/v1/auth/login"),
            Map.of("email", merchant.email(), "password", merchant.password()),
            Map.class
        );

        // L'email a été changé → INVALID_CREDENTIALS (l'email n'existe plus)
        assertThat(loginResponse.getStatusCode()).isIn(
            HttpStatus.UNAUTHORIZED, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("DELETE /gdpr/data sans authentification → 401")
    void anonymizeGdprData_unauthenticated_returns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/merchants/gdpr/data"),
            HttpMethod.DELETE,
            HttpEntity.EMPTY,
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Un marchand ne peut pas exporter les données d'un autre marchand")
    void exportGdprData_cannotAccessOtherMerchantData() {
        // Le marchand B tente d'accéder aux données du marchand A
        // L'endpoint retourne les données du principal connecté — pas besoin d'un ID en chemin
        // Mais le test vérifie que les données retournées sont bien celles du connecté (B)
        TestDataFactory.MerchantCredentials merchantB = factory.registerMerchant(restTemplate, url(""));

        ResponseEntity<Map> responseForB = restTemplate.exchange(
            url("/v1/merchants/gdpr/export"),
            HttpMethod.GET,
            new HttpEntity<>(factory.bearerHeaders(merchantB.accessToken())),
            Map.class
        );

        assertThat(responseForB.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> account = (Map<?, ?>) ((Map<?, ?>) responseForB.getBody().get("data")).get("account");

        // Les données retournées appartiennent bien à B (pas à A)
        assertThat(account.get("merchantId")).isEqualTo(merchantB.merchantId().toString());
        assertThat(account.get("email")).isEqualTo(merchantB.email());
        assertThat(account.get("merchantId")).isNotEqualTo(merchant.merchantId().toString());
    }
}
