package com.ebithex.auth;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.domain.ApiKey;
import com.ebithex.merchant.infrastructure.ApiKeyRepository;
import com.ebithex.shared.apikey.ApiKeyScope;
import com.ebithex.shared.apikey.ApiKeyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration — Gestion avancée des clés API.
 *
 * Couvre :
 *  - Création d'une clé scopée (PAYMENTS_WRITE+READ)
 *  - Clé scopée acceptée pour les paiements
 *  - Clé scopée refusée pour les payouts (scope manquant)
 *  - Rotation de clé avec grace period
 *  - Révocation immédiate d'une clé
 *  - Expiration d'une clé
 *  - Restriction IP
 *  - Listing des clés
 */
@DisplayName("Clés API — Scopes, rotation, expiration, restriction IP")
class ApiKeyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory  factory;
    @Autowired private ApiKeyService    apiKeyService;
    @Autowired private ApiKeyRepository apiKeyRepository;

    // ── Scopes ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Clé FULL_ACCESS → accès à tous les endpoints")
    void fullAccessKey_allowsAllEndpoints() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();

        // GET /v1/merchants/me avec clé FULL_ACCESS (créée par registerKycVerifiedMerchant)
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/merchants/me"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(creds.apiKey())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Clé PAYMENTS_WRITE → paiement autorisé")
    void paymentsWriteScope_allowsPayments() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();
        String scopedKey = factory.createScopedKey(creds.merchantId(),
            ApiKeyType.LIVE, Set.of(ApiKeyScope.PAYMENTS_WRITE, ApiKeyScope.PAYMENTS_READ));

        Map<String, Object> body = Map.of(
            "amount", new java.math.BigDecimal("5000"),
            "phoneNumber", "+2250501" + String.format("%05d", System.nanoTime() % 100000),
            "merchantReference", "SCOPE-TEST-" + UUID.randomUUID()
        );
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(scopedKey)), Map.class);

        // Doit passer la vérification de scope (peut échouer sur l'opérateur — c'est OK)
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Clé PAYOUTS_READ → endpoint paiements refusé (403)")
    void payoutsReadScope_forbidsPayments() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();
        String scopedKey = factory.createScopedKey(creds.merchantId(),
            ApiKeyType.LIVE, Set.of(ApiKeyScope.PAYOUTS_READ));

        Map<String, Object> body = Map.of(
            "amount", new java.math.BigDecimal("5000"),
            "phoneNumber", "+2250501" + String.format("%05d", System.nanoTime() % 100000),
            "merchantReference", "SCOPE-BLOCKED-" + UUID.randomUUID()
        );
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(scopedKey)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Listing ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Listing des clés → retourne les clés du marchand")
    void listKeys_returnsOwnKeys() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();
        // Créer une clé supplémentaire scopée
        factory.createScopedKey(creds.merchantId(), ApiKeyType.LIVE,
            Set.of(ApiKeyScope.PAYMENTS_WRITE));

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/auth/api-keys"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(creds.apiKey())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> keys = (List<?>) resp.getBody().get("data");
        // Au moins 2 clés : la live initiale + la scopée (+ éventuellement la test initiale)
        assertThat(keys).hasSizeGreaterThanOrEqualTo(2);
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rotation → nouvelle clé valide, ancienne valide pendant grace period")
    void rotation_oldKeyStillValidDuringGracePeriod() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();
        String oldKey = creds.apiKey();

        // Trouver l'ID de la clé courante
        List<ApiKey> keys = apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(creds.merchantId());
        ApiKey liveKey = keys.stream()
            .filter(k -> k.getType() == ApiKeyType.LIVE && k.isActive())
            .findFirst().orElseThrow();

        // Tourner la clé
        String newKey = apiKeyService.rotateKey(liveKey.getId(), creds.merchantId());
        assertThat(newKey).startsWith("ap_live_");
        assertThat(newKey).isNotEqualTo(oldKey);

        // La nouvelle clé fonctionne
        ResponseEntity<Map> respNew = restTemplate.exchange(
            url("/v1/merchants/me"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(newKey)), Map.class);
        assertThat(respNew.getStatusCode()).isEqualTo(HttpStatus.OK);

        // L'ancienne clé fonctionne encore (grace period)
        ResponseEntity<Map> respOld = restTemplate.exchange(
            url("/v1/merchants/me"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(oldKey)), Map.class);
        assertThat(respOld.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Rotation via endpoint → 200, nouvelle clé retournée")
    void rotation_viaEndpoint_returnsNewKey() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();
        List<ApiKey> keys = apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(creds.merchantId());
        UUID keyId = keys.stream()
            .filter(k -> k.getType() == ApiKeyType.LIVE && k.isActive())
            .findFirst().orElseThrow().getId();

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/auth/api-keys/" + keyId + "/rotate"), HttpMethod.POST,
            new HttpEntity<>(factory.apiKeyHeaders(creds.apiKey())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?,?> data = (Map<?,?>) resp.getBody().get("data");
        assertThat(data.get("rawKey")).asString().startsWith("ap_live_");
        assertThat(data.get("gracePeriodHours")).isEqualTo(24);
    }

    // ── Révocation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Révocation → clé immédiatement invalide (401)")
    void revocation_keyImmediatelyInvalid() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();
        // Créer une clé secondaire pour la révoquer (sans perdre l'accès)
        String secondKey = factory.createScopedKey(creds.merchantId(),
            ApiKeyType.LIVE, Set.of(ApiKeyScope.FULL_ACCESS));

        List<ApiKey> keys = apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(creds.merchantId());
        UUID secondKeyId = keys.stream()
            .filter(k -> ApiKeyService.sha256(secondKey).equals(k.getKeyHash()))
            .findFirst().orElseThrow().getId();

        // Révoquer via l'endpoint
        ResponseEntity<Map> revokeResp = restTemplate.exchange(
            url("/v1/auth/api-keys/" + secondKeyId), HttpMethod.DELETE,
            new HttpEntity<>(factory.apiKeyHeaders(creds.apiKey())), Map.class);
        assertThat(revokeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // La clé révoquée est maintenant rejetée
        ResponseEntity<Map> afterResp = restTemplate.exchange(
            url("/v1/merchants/me"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(secondKey)), Map.class);
        assertThat(afterResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Expiration ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Clé expirée → rejetée (401)")
    void expiredKey_isRejected() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();

        // Créer une clé avec une expiration dans le passé (déjà expirée)
        String expiredKey = apiKeyService.createKey(
            creds.merchantId(), ApiKeyType.LIVE, "Expired key",
            Set.of(ApiKeyScope.FULL_ACCESS), null,
            LocalDateTime.now().plusSeconds(1)); // expire dans 1 seconde

        // Forcer l'expiration en modifiant directement la date en base
        List<ApiKey> keys = apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(creds.merchantId());
        ApiKey expiredApiKey = keys.stream()
            .filter(k -> ApiKeyService.sha256(expiredKey).equals(k.getKeyHash()))
            .findFirst().orElseThrow();
        expiredApiKey.setExpiresAt(LocalDateTime.now().minusHours(1));
        apiKeyRepository.save(expiredApiKey);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/merchants/me"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(expiredKey)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Restriction IP ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Clé avec restriction IP → IP non autorisée rejetée (403)")
    void ipRestriction_unknownIp_isForbidden() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();

        // Créer une clé avec une IP qui ne correspond pas à l'IP de test (127.0.0.1)
        String restrictedKey = apiKeyService.createKey(
            creds.merchantId(), ApiKeyType.LIVE, "IP restricted key",
            Set.of(ApiKeyScope.FULL_ACCESS),
            Set.of("10.0.0.1"),  // IP inconnue — 127.0.0.1 est l'IP de test
            null);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/merchants/me"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(restrictedKey)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Clé avec restriction IP → IP autorisée acceptée")
    void ipRestriction_allowedIp_isAccepted() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();

        // 127.0.0.1 est l'IP utilisée par TestRestTemplate
        String restrictedKey = apiKeyService.createKey(
            creds.merchantId(), ApiKeyType.LIVE, "IP restricted key",
            Set.of(ApiKeyScope.FULL_ACCESS),
            Set.of("127.0.0.1"),
            null);

        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/merchants/me"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(restrictedKey)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Mise à jour des paramètres ────────────────────────────────────────────

    @Test
    @DisplayName("Mise à jour des scopes → nouvelle restriction immédiate")
    void updateScopes_newScopesApplied() {
        TestDataFactory.MerchantCredentials creds = factory.registerKycVerifiedMerchant();
        String key = factory.createScopedKey(creds.merchantId(),
            ApiKeyType.LIVE, Set.of(ApiKeyScope.FULL_ACCESS));

        List<ApiKey> keys = apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(creds.merchantId());
        UUID keyId = keys.stream()
            .filter(k -> ApiKeyService.sha256(key).equals(k.getKeyHash()))
            .findFirst().orElseThrow().getId();

        // Restreindre aux payouts uniquement
        Map<String, Object> body = Map.of("scopes", Set.of("PAYOUTS_READ"));
        restTemplate.exchange(
            url("/v1/auth/api-keys/" + keyId + "/scopes"), HttpMethod.PUT,
            new HttpEntity<>(body, factory.apiKeyHeaders(creds.apiKey())), Map.class);

        // Tentative de paiement → désormais refusée
        Map<String, Object> payBody = Map.of(
            "amount", new java.math.BigDecimal("5000"),
            "phoneNumber", "+2250501" + String.format("%05d", System.nanoTime() % 100000),
            "merchantReference", "SCOPE-UPDATE-" + UUID.randomUUID()
        );
        ResponseEntity<Map> resp = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(payBody, factory.apiKeyHeaders(key)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
