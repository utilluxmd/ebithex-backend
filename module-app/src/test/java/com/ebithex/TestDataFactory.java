package com.ebithex;

import com.ebithex.merchant.application.ApiKeyService;
import com.ebithex.merchant.domain.*;
import com.ebithex.merchant.infrastructure.ApiKeyRepository;
import com.ebithex.merchant.infrastructure.KycDocumentRepository;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.merchant.infrastructure.StaffUserRepository;
import com.ebithex.shared.apikey.ApiKeyScope;
import com.ebithex.shared.apikey.ApiKeyType;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.shared.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Usine de données de test.
 *
 * Fournit :
 *  - Création de marchands via l'API REST (test réaliste du flow complet)
 *  - Création directe en DB pour les cas nécessitant un état précis (KYC approuvé)
 *  - Principals admin pour les tests back-office via MockMvc
 */
@Component
@RequiredArgsConstructor
public class TestDataFactory {

    private final MerchantRepository     merchantRepository;
    private final StaffUserRepository    staffUserRepository;
    private final PasswordEncoder        passwordEncoder;
    private final KycDocumentRepository  kycDocumentRepository;
    private final ApiKeyService          apiKeyService;
    private final ApiKeyRepository       apiKeyRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    /** Créé un email unique par test pour éviter les collisions. */
    public String uniqueEmail() {
        return "merchant-" + COUNTER.incrementAndGet() + "@test.ebithex.io";
    }

    /**
     * Enregistre un marchand via l'API REST et retourne ses credentials.
     * Utilise TestRestTemplate pour un test end-to-end du flow register.
     */
    public MerchantCredentials registerMerchant(TestRestTemplate rest, String baseUrl) {
        String email    = uniqueEmail();
        String password = "Test@1234!";
        String country  = "CI";

        Map<String, String> body = Map.of(
            "businessName", "Test Merchant " + COUNTER.get(),
            "email",        email,
            "password",     password,
            "country",      country
        );

        ResponseEntity<Map> response = rest.postForEntity(
            baseUrl + "/v1/auth/register", body, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Registration failed: " + response.getBody());
        }

        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return new MerchantCredentials(
            UUID.fromString((String) data.get("merchantId")),
            email,
            password,
            (String) data.get("liveApiKey"),
            (String) data.get("accessToken"),
            (String) data.get("refreshToken"),
            country
        );
    }

    /**
     * Enregistre un marchand directement en DB avec KYC approuvé.
     * Utile pour les tests de payout (require MERCHANT_KYC_VERIFIED).
     */
    public MerchantCredentials registerKycVerifiedMerchant() {
        String email    = uniqueEmail();
        String password = "Test@1234!";

        Merchant merchant = Merchant.builder()
            .businessName("KYC Verified Merchant " + COUNTER.get())
            .email(email)
            .hashedSecret(passwordEncoder.encode(password))
            .country("CI")
            .active(true)
            .kycVerified(true)
            .kycStatus(KycStatus.APPROVED)
            .build();
        merchant = merchantRepository.save(merchant);

        String[] keys = apiKeyService.createInitialKeys(merchant.getId());
        return new MerchantCredentials(
            merchant.getId(), email, password, keys[0], null, null, "CI");
    }

    /**
     * Enregistre un marchand avec un pays donné, directement en DB.
     */
    public MerchantCredentials registerMerchantInCountry(String country) {
        String email = uniqueEmail();

        Merchant merchant = Merchant.builder()
            .businessName("Merchant-" + country + "-" + COUNTER.get())
            .email(email)
            .hashedSecret(passwordEncoder.encode("Test@1234!"))
            .country(country)
            .active(true)
            .kycVerified(false)
            .kycStatus(KycStatus.NONE)
            .build();
        merchant = merchantRepository.save(merchant);

        String[] keys = apiKeyService.createInitialKeys(merchant.getId());
        return new MerchantCredentials(
            merchant.getId(), email, "Test@1234!", keys[0], null, null, country);
    }

    /**
     * Crée une clé avec des scopes restreints pour un marchand existant.
     * Utile pour tester la vérification de scope dans les tests d'intégration.
     */
    public String createScopedKey(UUID merchantId, ApiKeyType type, Set<ApiKeyScope> scopes) {
        return apiKeyService.createKey(merchantId, type, "Test scoped key", scopes, null, null);
    }

    /**
     * Seeds all required KYC documents (ACCEPTED) for a merchant directly in the DB.
     * Call this before submitting a KYC dossier in tests that exercise the submit flow.
     */
    public void seedRequiredKycDocuments(UUID merchantId) {
        for (KycDocumentType type : KycDocumentType.REQUIRED) {
            KycDocument doc = KycDocument.builder()
                .merchantId(merchantId)
                .documentType(type)
                .status(KycDocumentStatus.ACCEPTED)
                .storageKey("test/" + merchantId + "/" + type.name().toLowerCase() + ".pdf")
                .fileName("test-" + type.name().toLowerCase() + ".pdf")
                .contentType("application/pdf")
                .fileSizeBytes(1024L)
                .checksumSha256((UUID.randomUUID().toString() + UUID.randomUUID().toString())
                    .replace("-", "").substring(0, 64))
                .build();
            kycDocumentRepository.save(doc);
        }
    }

    /**
     * Headers HTTP pour l'authentification par API key.
     */
    public HttpHeaders apiKeyHeaders(String rawApiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", rawApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Headers HTTP pour l'authentification par JWT Bearer.
     */
    public HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ── Utilisateurs back-office (StaffUser) ─────────────────────────────────

    /**
     * Crée un utilisateur back-office directement en DB et retourne ses credentials.
     * Utilisé pour les tests d'authentification admin.
     */
    public StaffUserCredentials createStaffUser(Role role, String country) {
        String email    = "staff-" + COUNTER.incrementAndGet() + "@ebithex.io";
        String password = "Admin@1234!";

        StaffUser user = StaffUser.builder()
            .email(email)
            .hashedPassword(passwordEncoder.encode(password))
            .role(role)
            .country(country)
            .active(true)
            .twoFactorEnabled(false)
            .build();

        user = staffUserRepository.save(user);
        return new StaffUserCredentials(user.getId(), email, password, role, country);
    }

    public StaffUserCredentials createStaffUser(Role role) {
        return createStaffUser(role, null);
    }

    /** @deprecated Use {@link #createStaffUser(Role, String)} */
    @Deprecated
    public StaffUserCredentials createOperator(Role role, String country) {
        return createStaffUser(role, country);
    }

    /** @deprecated Use {@link #createStaffUser(Role)} */
    @Deprecated
    public StaffUserCredentials createOperator(Role role) {
        return createStaffUser(role, null);
    }

    // ── Principals admin pour MockMvc ────────────────────────────────────────

    public EbithexPrincipal adminPrincipal() {
        return EbithexPrincipal.builder()
            .id(UUID.randomUUID())
            .email("admin@ebithex.io")
            .roles(Set.of(Role.ADMIN))
            .active(true)
            .build();
    }

    public EbithexPrincipal superAdminPrincipal() {
        return EbithexPrincipal.builder()
            .id(UUID.randomUUID())
            .email("superadmin@ebithex.io")
            .roles(Set.of(Role.SUPER_ADMIN))
            .active(true)
            .build();
    }

    public EbithexPrincipal countryAdminPrincipal(String country) {
        return EbithexPrincipal.builder()
            .id(UUID.randomUUID())
            .email("admin-" + country.toLowerCase() + "@ebithex.io")
            .roles(Set.of(Role.COUNTRY_ADMIN))
            .active(true)
            .country(country)
            .build();
    }

    public EbithexPrincipal financePrincipal() {
        return EbithexPrincipal.builder()
            .id(UUID.randomUUID())
            .email("finance@ebithex.io")
            .roles(Set.of(Role.FINANCE))
            .active(true)
            .build();
    }

    public EbithexPrincipal reconciliationPrincipal() {
        return EbithexPrincipal.builder()
            .id(UUID.randomUUID())
            .email("reconciliation@ebithex.io")
            .roles(Set.of(Role.RECONCILIATION))
            .active(true)
            .build();
    }

    public EbithexPrincipal supportPrincipal() {
        return EbithexPrincipal.builder()
            .id(UUID.randomUUID())
            .email("support@ebithex.io")
            .roles(Set.of(Role.SUPPORT))
            .active(true)
            .build();
    }

    // ── DTO ─────────────────────────────────────────────────────────────────

    public record StaffUserCredentials(
        UUID   staffUserId,
        String email,
        String password,
        Role   role,
        String country
    ) {}

    /** @deprecated Use {@link StaffUserCredentials} */
    @Deprecated
    public record OperatorCredentials(
        UUID   operatorId,
        String email,
        String password,
        Role   role,
        String country
    ) {}

    public record MerchantCredentials(
        UUID   merchantId,
        String email,
        String password,
        String apiKey,
        String accessToken,
        String refreshToken,
        String country
    ) {}
}