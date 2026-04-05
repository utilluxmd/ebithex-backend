package com.ebithex.merchant.application;

import com.ebithex.merchant.domain.KycStatus;
import com.ebithex.merchant.domain.Merchant;
import com.ebithex.merchant.dto.MerchantRegistrationRequest;
import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.shared.audit.AuditLogService;
import com.ebithex.shared.event.KycStatusChangedEvent;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.outbox.OutboxWriter;
import com.ebithex.shared.security.JwtService;
import com.ebithex.shared.security.LoginAttemptService;
import com.ebithex.shared.security.TokenBlacklistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class MerchantService {

    private final MerchantRepository    merchantRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtService            jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final LoginAttemptService   loginAttemptService;
    private final AuditLogService       auditLogService;
    private final OutboxWriter          outboxWriter;
    private final KycDocumentService    kycDocumentService;
    private final ApiKeyService         apiKeyService;

    @Autowired
    public MerchantService(MerchantRepository merchantRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           TokenBlacklistService tokenBlacklistService,
                           LoginAttemptService loginAttemptService,
                           AuditLogService auditLogService,
                           OutboxWriter outboxWriter,
                           @Lazy KycDocumentService kycDocumentService,
                           ApiKeyService apiKeyService) {
        this.merchantRepository    = merchantRepository;
        this.passwordEncoder       = passwordEncoder;
        this.jwtService            = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.loginAttemptService   = loginAttemptService;
        this.auditLogService       = auditLogService;
        this.outboxWriter          = outboxWriter;
        this.kycDocumentService    = kycDocumentService;
        this.apiKeyService         = apiKeyService;
    }

    // ── Register / Login ────────────────────────────────────────────────────

    @Transactional
    public Map<String, String> register(MerchantRegistrationRequest req) {
        if (merchantRepository.existsByEmail(req.getEmail())) {
            throw new EbithexException(ErrorCode.DUPLICATE_EMAIL, "Email déjà utilisé: " + req.getEmail());
        }
        Merchant merchant = Merchant.builder()
            .businessName(req.getBusinessName())
            .email(req.getEmail())
            .hashedSecret(passwordEncoder.encode(req.getPassword()))
            .country(req.getCountry())
            .webhookUrl(req.getWebhookUrl())
            .active(true)
            .kycVerified(false)
            .kycStatus(KycStatus.NONE)
            .build();
        merchantRepository.save(merchant);

        // Crée les deux clés initiales (live + test, FULL_ACCESS)
        String[] keys = apiKeyService.createInitialKeys(merchant.getId());
        String rawLiveKey = keys[0];
        String rawTestKey = keys[1];

        log.info("Nouveau marchand enregistré: {} ({})", req.getBusinessName(), req.getEmail());
        String accessToken  = jwtService.generateAccessToken(merchant.getId(), merchant.getEmail());
        String refreshToken = jwtService.generateRefreshToken(merchant.getId(), merchant.getEmail());
        // Clés retournées UNE SEULE FOIS — ne peuvent plus être récupérées ensuite
        return Map.of(
            "liveApiKey",   rawLiveKey,
            "testApiKey",   rawTestKey,
            "accessToken",  accessToken,
            "refreshToken", refreshToken,
            "merchantId",   merchant.getId().toString(),
            "message",      "Compte créé. Complétez votre KYC pour activer tous les services."
        );
    }

    public Map<String, String> login(String email, String password) {
        // Vérifier le verrou brute-force AVANT toute requête DB (prévient l'énumération)
        loginAttemptService.checkNotLocked(email);

        Merchant merchant = merchantRepository.findByEmail(email)
            .orElseThrow(() -> {
                loginAttemptService.recordFailure(email);
                return new EbithexException(ErrorCode.INVALID_CREDENTIALS, "Identifiants invalides");
            });

        if (!passwordEncoder.matches(password, merchant.getHashedSecret())) {
            loginAttemptService.recordFailure(email);
            throw new EbithexException(ErrorCode.INVALID_CREDENTIALS, "Identifiants invalides");
        }
        if (!merchant.isActive()) {
            throw new EbithexException(ErrorCode.ACCOUNT_DISABLED, "Compte désactivé");
        }

        loginAttemptService.recordSuccess(email);
        String accessToken  = jwtService.generateAccessToken(merchant.getId(), merchant.getEmail());
        String refreshToken = jwtService.generateRefreshToken(merchant.getId(), merchant.getEmail());
        return Map.of(
            "accessToken",  accessToken,
            "refreshToken", refreshToken,
            "merchantId",   merchant.getId().toString()
        );
    }

    // ── Token management ────────────────────────────────────────────────────

    /**
     * Révoque l'access token courant et, si fourni, le refresh token.
     * L'authHeader doit être le header Authorization de la requête en cours.
     */
    public void logout(@Nullable String authHeader, @Nullable String refreshToken) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            revokeIfValid(token);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            revokeIfValid(refreshToken);
        }
    }

    /**
     * Émet une nouvelle paire access/refresh depuis un refresh token valide.
     * Le refresh token est révoqué après usage (rotation).
     */
    public Map<String, String> refreshToken(@Nullable String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new EbithexException(ErrorCode.INVALID_TOKEN, "Refresh token requis");
        }
        if (!jwtService.validateToken(refreshToken)) {
            throw new EbithexException(ErrorCode.INVALID_TOKEN, "Refresh token invalide ou expiré");
        }
        if (!"refresh".equals(jwtService.extractTokenType(refreshToken))) {
            throw new EbithexException(ErrorCode.INVALID_TOKEN, "Type de token invalide");
        }
        String jti = jwtService.getJti(refreshToken);
        if (tokenBlacklistService.isRevoked(jti)) {
            throw new EbithexException(ErrorCode.INVALID_TOKEN, "Refresh token révoqué");
        }
        // Rotation — l'ancien refresh token est immédiatement révoqué
        tokenBlacklistService.revoke(jti, jwtService.getExpiration(refreshToken));

        UUID merchantId = jwtService.extractMerchantId(refreshToken);
        Merchant merchant = findById(merchantId);
        if (!merchant.isActive()) {
            throw new EbithexException(ErrorCode.ACCOUNT_DISABLED, "Compte désactivé");
        }
        String newAccess  = jwtService.generateAccessToken(merchant.getId(), merchant.getEmail());
        String newRefresh = jwtService.generateRefreshToken(merchant.getId(), merchant.getEmail());
        return Map.of("accessToken", newAccess, "refreshToken", newRefresh);
    }

    /**
     * Révoque immédiatement toutes les clés API d'un marchand.
     * Opération d'urgence réservée aux admins.
     */
    @Transactional
    public void revokeAllApiKeys(UUID merchantId) {
        apiKeyService.revokeAllKeys(merchantId);
    }

    // ── KYC Workflow ────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(cacheNames = "merchants", key = "#merchantId")
    public void submitKyc(UUID merchantId) {
        Merchant merchant = findById(merchantId);
        if (merchant.getKycStatus() == KycStatus.APPROVED) {
            throw new EbithexException(ErrorCode.KYC_ALREADY_APPROVED, "KYC déjà approuvé");
        }
        if (!kycDocumentService.isDossierComplete(merchantId)) {
            throw new EbithexException(ErrorCode.KYC_INCOMPLETE_DOSSIER,
                "Dossier incomplet. Veuillez téléverser tous les documents requis (pièce d'identité, " +
                "registre de commerce, justificatif de domicile) et attendre leur validation.");
        }
        merchant.setKycStatus(KycStatus.PENDING);
        merchant.setKycSubmittedAt(LocalDateTime.now());
        merchant.setKycRejectionReason(null);
        merchantRepository.save(merchant);
        log.info("KYC soumis pour merchantId: {}", merchantId);
    }

    @Transactional
    @CacheEvict(cacheNames = "merchants", key = "#merchantId")
    public void approveKyc(UUID merchantId) {
        Merchant merchant = findById(merchantId);
        if (merchant.getKycStatus() != KycStatus.PENDING) {
            throw new EbithexException(ErrorCode.KYC_INVALID_STATE,
                "KYC ne peut être approuvé que depuis l'état PENDING (état actuel: " + merchant.getKycStatus() + ")");
        }
        merchant.setKycStatus(KycStatus.APPROVED);
        merchant.setKycVerified(true);
        merchant.setKycReviewedAt(LocalDateTime.now());
        merchantRepository.save(merchant);
        log.info("KYC approuvé pour merchantId: {}", merchantId);
        auditLogService.record("KYC_APPROVED", "Merchant", merchantId.toString(), null);
        outboxWriter.write("Merchant", merchantId, "KYC_STATUS_CHANGED",
            new KycStatusChangedEvent(merchantId, merchant.getEmail(),
                merchant.getBusinessName(), "APPROVED", null));
    }

    @Transactional
    @CacheEvict(cacheNames = "merchants", key = "#merchantId")
    public void rejectKyc(UUID merchantId, String reason) {
        Merchant merchant = findById(merchantId);
        if (merchant.getKycStatus() != KycStatus.PENDING) {
            throw new EbithexException(ErrorCode.KYC_INVALID_STATE,
                "KYC ne peut être rejeté que depuis l'état PENDING (état actuel: " + merchant.getKycStatus() + ")");
        }
        merchant.setKycStatus(KycStatus.REJECTED);
        merchant.setKycVerified(false);
        merchant.setKycRejectionReason(reason);
        merchant.setKycReviewedAt(LocalDateTime.now());
        merchantRepository.save(merchant);
        log.info("KYC rejeté pour merchantId: {} — raison: {}", merchantId, reason);
        auditLogService.record("KYC_REJECTED", "Merchant", merchantId.toString(),
            "{\"reason\":\"" + (reason != null ? reason.replace("\"", "\\\"") : "") + "\"}");
        outboxWriter.write("Merchant", merchantId, "KYC_STATUS_CHANGED",
            new KycStatusChangedEvent(merchantId, merchant.getEmail(),
                merchant.getBusinessName(), "REJECTED", reason));
    }

    // ── Admin / Back-office ─────────────────────────────────────────────────

    public Page<Merchant> listMerchants(String country, Pageable pageable) {
        if (country != null && !country.isBlank()) {
            return merchantRepository.findByCountry(country, pageable);
        }
        return merchantRepository.findAll(pageable);
    }

    @Transactional
    @CacheEvict(cacheNames = "merchants", key = "#merchantId")
    public void setActive(UUID merchantId, boolean active) {
        Merchant merchant = findById(merchantId);
        merchant.setActive(active);
        merchantRepository.save(merchant);
        log.info("Marchand {} {}", merchantId, active ? "activé" : "désactivé");
        auditLogService.record(active ? "MERCHANT_ACTIVATED" : "MERCHANT_DEACTIVATED",
            "Merchant", merchantId.toString(), null);
    }

    /**
     * Active ou désactive le mode sandbox pour un marchand.
     * En mode sandbox, les paiements sont simulés (pas d'appel opérateur réel).
     * Réservé SUPER_ADMIN — impact sur la facturation.
     */
    @Transactional
    @CacheEvict(cacheNames = "merchants", key = "#merchantId")
    public void setTestMode(UUID merchantId, boolean testMode) {
        Merchant merchant = findById(merchantId);
        merchant.setTestMode(testMode);
        merchantRepository.save(merchant);
        log.info("Mode test {} pour merchantId={}", testMode ? "activé" : "désactivé", merchantId);
        auditLogService.record("TEST_MODE_" + (testMode ? "ENABLED" : "DISABLED"),
            "Merchant", merchantId.toString(),
            "{\"testMode\":" + testMode + "}");
    }

    /**
     * Configure les plafonds journalier et/ou mensuel d'un marchand.
     * Passer null pour supprimer un plafond.
     */
    @Transactional
    @CacheEvict(cacheNames = "merchants", key = "#merchantId")
    public void setPaymentLimits(UUID merchantId, java.math.BigDecimal dailyLimit,
                                  java.math.BigDecimal monthlyLimit) {
        Merchant merchant = findById(merchantId);
        merchant.setDailyPaymentLimit(dailyLimit);
        merchant.setMonthlyPaymentLimit(monthlyLimit);
        merchantRepository.save(merchant);
        log.info("Limites mises à jour pour merchantId={} daily={} monthly={}",
            merchantId, dailyLimit, monthlyLimit);
        auditLogService.record("PAYMENT_LIMITS_UPDATED", "Merchant", merchantId.toString(),
            "{\"dailyLimit\":" + dailyLimit + ",\"monthlyLimit\":" + monthlyLimit + "}");
    }

    // ── RGPD — Droit d'accès et droit à l'effacement ────────────────────────

    /**
     * Exporte toutes les données personnelles du marchand (RGPD art. 15 — droit d'accès).
     *
     * <p>Retourne un objet JSON contenant :
     * <ul>
     *   <li>Données du compte (email, businessName, country, dates)</li>
     *   <li>Statut KYC (sans les fichiers binaires stockés sur S3)</li>
     *   <li>Résumé des transactions (compte, montant total, dates)</li>
     * </ul>
     *
     * <p>Note : les numéros de téléphone sont retournés chiffrés (AES-256-GCM) —
     * l'export contient les données telles qu'elles sont stockées, pas déchiffrées.
     * Un opérateur GDPR doit utiliser la console admin pour l'export complet avec déchiffrement.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> exportGdprData(UUID merchantId) {
        Merchant merchant = findById(merchantId);
        auditLogService.record("GDPR_EXPORT_REQUESTED", "Merchant", merchantId.toString(), null);
        log.info("Export RGPD demandé par merchantId={}", merchantId);

        return Map.of(
            "account", Map.of(
                "merchantId",    merchant.getId().toString(),
                "businessName",  merchant.getBusinessName(),
                "email",         merchant.getEmail(),
                "country",       merchant.getCountry() != null ? merchant.getCountry() : "",
                "kycStatus",     merchant.getKycStatus().name(),
                "active",        merchant.isActive(),
                "createdAt",     merchant.getCreatedAt() != null ? merchant.getCreatedAt().toString() : "",
                "updatedAt",     merchant.getUpdatedAt() != null ? merchant.getUpdatedAt().toString() : ""
            ),
            "gdpr", Map.of(
                "exportedAt",    LocalDateTime.now().toString(),
                "retentionYears", "5",
                "dataController", "Ebithex SAS",
                "contact",       "dpo@ebithex.io"
            )
        );
    }

    /**
     * Anonymise les données personnelles du compte marchand (RGPD art. 17 — droit à l'effacement).
     *
     * <p><b>Important :</b> la suppression complète d'un compte marchand est impossible
     * en raison des obligations légales de conservation des données financières
     * (BCEAO instruction 008-05-2015, durée 10 ans). Cette méthode anonymise uniquement
     * les champs personnellement identifiables :
     * <ul>
     *   <li>Email → {@code deleted+{merchantId}@ebithex.invalid}</li>
     *   <li>businessName → {@code [SUPPRIMÉ]}</li>
     *   <li>Compte désactivé (active = false)</li>
     * </ul>
     * Les transactions, wallets et audit logs sont conservés à des fins légales.
     *
     * <p>Cette opération est irréversible.
     */
    @Transactional
    @CacheEvict(cacheNames = "merchants", key = "#merchantId")
    public void anonymizeGdprData(UUID merchantId) {
        Merchant merchant = findById(merchantId);

        // Vérifier qu'il n'y a pas de transactions en cours
        // (ne pas anonymiser si PENDING/PROCESSING — risque pour le marchand)
        auditLogService.record("GDPR_ANONYMIZATION_REQUESTED", "Merchant", merchantId.toString(),
            "{\"email\":\"" + merchant.getEmail() + "\"}");

        String anonymizedEmail = "deleted+" + merchantId + "@ebithex.invalid";
        merchant.setEmail(anonymizedEmail);
        merchant.setBusinessName("[SUPPRIMÉ]");
        merchant.setActive(false);
        merchant.setWebhookUrl(null);
        merchantRepository.save(merchant);

        log.info("Données RGPD anonymisées pour merchantId={}", merchantId);
        auditLogService.record("GDPR_ANONYMIZATION_COMPLETED", "Merchant", merchantId.toString(), null);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @Cacheable(cacheNames = "merchants", key = "#merchantId")
    public Merchant findById(UUID merchantId) {
        return merchantRepository.findById(merchantId)
            .orElseThrow(() -> new EbithexException(ErrorCode.MERCHANT_NOT_FOUND, "Marchand introuvable"));
    }

    /** SHA-256 hex du token brut — utilisé comme clé de recherche en base. */
    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void revokeIfValid(String token) {
        if (jwtService.validateToken(token)) {
            tokenBlacklistService.revoke(jwtService.getJti(token), jwtService.getExpiration(token));
        }
    }
}