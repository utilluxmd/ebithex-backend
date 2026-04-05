package com.ebithex.merchant.application;

import com.ebithex.merchant.domain.ApiKey;
import com.ebithex.merchant.infrastructure.ApiKeyRepository;
import com.ebithex.shared.apikey.ApiKeyScope;
import com.ebithex.shared.apikey.ApiKeyType;
import com.ebithex.shared.audit.AuditLogService;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gestion du cycle de vie des clés API marchands.
 *
 * <p>Chaque clé est stockée uniquement sous forme de hash SHA-256.
 * La valeur brute est générée, retournée une seule fois, et jamais persistée.
 *
 * <p><b>Rotation</b> : la rotation crée un nouvel enregistrement {@link ApiKey}
 * et désactive l'ancien. Le nouveau enregistrement porte le hash de l'ancien comme
 * {@code previousHash}, valide pendant la période de grâce (défaut 24h).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private final ApiKeyRepository  apiKeyRepository;
    private final AuditLogService   auditLogService;
    private final SecureRandom      secureRandom = new SecureRandom();

    @Value("${ebithex.security.api-key.grace-period-hours:24}")
    private int gracePeriodHours;

    // ── Création ──────────────────────────────────────────────────────────────

    /**
     * Crée les deux clés initiales d'un marchand (live + test, FULL_ACCESS).
     * Appelé exclusivement depuis {@code MerchantService.register()}.
     *
     * @return tableau [rawLiveKey, rawTestKey]
     */
    @Transactional
    public String[] createInitialKeys(UUID merchantId) {
        String rawLive = generateRawKey(ApiKeyType.LIVE);
        String rawTest = generateRawKey(ApiKeyType.TEST);

        apiKeyRepository.save(buildKey(merchantId, rawLive, ApiKeyType.LIVE,
            "Clé principale", Set.of(ApiKeyScope.FULL_ACCESS), null, null));
        apiKeyRepository.save(buildKey(merchantId, rawTest, ApiKeyType.TEST,
            "Clé sandbox", Set.of(ApiKeyScope.FULL_ACCESS), null, null));

        log.info("Clés initiales créées pour merchantId={}", merchantId);
        return new String[]{rawLive, rawTest};
    }

    /**
     * Crée une nouvelle clé avec les paramètres fournis par le marchand.
     *
     * @return la valeur brute de la clé (retournée une seule fois)
     */
    @Transactional
    public String createKey(UUID merchantId, ApiKeyType type, String label,
                            Set<ApiKeyScope> scopes, Set<String> allowedIps,
                            LocalDateTime expiresAt) {
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            throw new EbithexException(ErrorCode.INVALID_EXPIRY, "La date d'expiration doit être dans le futur");
        }

        String rawKey = generateRawKey(type);
        ApiKey key = buildKey(merchantId, rawKey, type, label, scopes, allowedIps, expiresAt);
        apiKeyRepository.save(key);

        log.info("Clé créée: merchantId={} type={} scopes={}", merchantId, type,
            key.getScopes());
        auditLogService.record("API_KEY_CREATED", "ApiKey", key.getId().toString(),
            "{\"type\":\"" + type + "\",\"scopes\":\"" + key.getScopes() + "\"}");
        return rawKey;
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    /**
     * Tourne la clé identifiée par {@code keyId}.
     * L'ancienne clé est désactivée ; la nouvelle hérite de ses paramètres
     * (scopes, IP, expiry, label) et porte l'ancien hash comme {@code previousHash}
     * pour la période de grâce.
     *
     * @return la valeur brute de la nouvelle clé (retournée une seule fois)
     */
    @Transactional
    public String rotateKey(UUID keyId, UUID merchantId) {
        ApiKey old = findOwnedKey(keyId, merchantId);
        String rawNew = generateRawKey(old.getType());

        ApiKey newKey = ApiKey.builder()
            .merchantId(merchantId)
            .keyHash(sha256(rawNew))
            .keyHint(lastFour(rawNew))
            .prefix(old.getPrefix())
            .type(old.getType())
            .label(old.getLabel())
            .scopes(old.getScopes())
            .allowedIps(old.getAllowedIps())
            .expiresAt(old.getExpiresAt())
            .rotationRequiredDays(old.getRotationRequiredDays())
            .active(true)
            .previousHash(old.getKeyHash())
            .previousExpiresAt(LocalDateTime.now().plusHours(gracePeriodHours))
            .build();

        old.setActive(false);
        apiKeyRepository.save(old);
        apiKeyRepository.save(newKey);

        log.info("Clé tournée: merchantId={} oldId={} newId={} grace={}h",
            merchantId, keyId, newKey.getId(), gracePeriodHours);
        auditLogService.record("API_KEY_ROTATED", "ApiKey", newKey.getId().toString(),
            "{\"oldKeyId\":\"" + keyId + "\",\"gracePeriodHours\":" + gracePeriodHours + "}");
        return rawNew;
    }


    // ── Révocation ────────────────────────────────────────────────────────────

    /** Révoque une clé spécifique (marchand). */
    @Transactional
    public void revokeKey(UUID keyId, UUID merchantId) {
        ApiKey key = findOwnedKey(keyId, merchantId);
        key.setActive(false);
        apiKeyRepository.save(key);
        log.info("Clé révoquée: merchantId={} keyId={}", merchantId, keyId);
        auditLogService.record("API_KEY_REVOKED", "ApiKey", keyId.toString(), null);
    }

    /**
     * Révoque toutes les clés d'un marchand (urgence — admin uniquement).
     * Aucune période de grâce — effet immédiat.
     */
    @Transactional
    public void revokeAllKeys(UUID merchantId) {
        int count = apiKeyRepository.deactivateAllForMerchant(merchantId);
        log.warn("Révocation d'urgence: merchantId={} keysRevoked={}", merchantId, count);
        auditLogService.record("API_KEYS_REVOKED_ALL", "Merchant", merchantId.toString(),
            "{\"keysRevoked\":" + count + "}");
    }

    // ── Mise à jour des paramètres ────────────────────────────────────────────

    @Transactional
    public void updateScopes(UUID keyId, UUID merchantId, Set<ApiKeyScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new EbithexException(ErrorCode.INVALID_SCOPES, "Au moins un scope est requis");
        }
        ApiKey key = findOwnedKey(keyId, merchantId);
        key.setScopes(scopes.stream().map(ApiKeyScope::name).collect(Collectors.joining(",")));
        apiKeyRepository.save(key);
        log.info("Scopes mis à jour: keyId={} scopes={}", keyId, key.getScopes());
        auditLogService.record("API_KEY_SCOPES_UPDATED", "ApiKey", keyId.toString(),
            "{\"scopes\":\"" + key.getScopes() + "\"}");
    }

    @Transactional
    public void updateAllowedIps(UUID keyId, UUID merchantId, Set<String> ips) {
        ApiKey key = findOwnedKey(keyId, merchantId);
        key.setAllowedIps(ips == null || ips.isEmpty() ? null : String.join(",", ips));
        apiKeyRepository.save(key);
        log.info("Restriction IP mise à jour: keyId={} ips={}", keyId, key.getAllowedIps());
        auditLogService.record("API_KEY_IPS_UPDATED", "ApiKey", keyId.toString(),
            "{\"allowedIps\":\"" + key.getAllowedIps() + "\"}");
    }

    @Transactional
    public void updateExpiry(UUID keyId, UUID merchantId, LocalDateTime expiresAt) {
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            throw new EbithexException(ErrorCode.INVALID_EXPIRY, "La date d'expiration doit être dans le futur");
        }
        ApiKey key = findOwnedKey(keyId, merchantId);
        key.setExpiresAt(expiresAt);
        apiKeyRepository.save(key);
        log.info("Expiration mise à jour: keyId={} expiresAt={}", keyId, expiresAt);
        auditLogService.record("API_KEY_EXPIRY_UPDATED", "ApiKey", keyId.toString(),
            "{\"expiresAt\":\"" + expiresAt + "\"}");
    }

    /** Réservé SUPER_ADMIN — configure la politique de rotation forcée. */
    @Transactional
    public void setRotationPolicy(UUID keyId, Integer rotationRequiredDays) {
        ApiKey key = apiKeyRepository.findById(keyId)
            .orElseThrow(() -> new EbithexException(ErrorCode.API_KEY_NOT_FOUND, "Clé introuvable"));
        if (rotationRequiredDays != null && rotationRequiredDays < 1) {
            throw new EbithexException(ErrorCode.INVALID_ROTATION_POLICY,
                "Le nombre de jours doit être supérieur à 0");
        }
        key.setRotationRequiredDays(rotationRequiredDays);
        apiKeyRepository.save(key);
        log.info("Politique rotation mise à jour: keyId={} days={}", keyId, rotationRequiredDays);
        auditLogService.record("API_KEY_ROTATION_POLICY_UPDATED", "ApiKey", keyId.toString(),
            "{\"rotationRequiredDays\":" + rotationRequiredDays + "}");
    }

    // ── Listing ───────────────────────────────────────────────────────────────

    public List<ApiKey> listKeys(UUID merchantId) {
        return apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public List<ApiKey> listAllKeysForMerchant(UUID merchantId) {
        return apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    // ── lastUsedAt (appelé depuis le filtre — non transactionnel par défaut) ──

    @Async
    @Transactional
    public void touchLastUsed(UUID keyId) {
        apiKeyRepository.updateLastUsedAt(keyId, LocalDateTime.now());
    }

    // ── Aging (appelé depuis ApiKeyAgingJob) ──────────────────────────────────

    @Transactional
    public void markAgingReminderSent(UUID keyId) {
        apiKeyRepository.findById(keyId).ifPresent(k -> {
            k.setAgingReminderSentAt(LocalDateTime.now());
            apiKeyRepository.save(k);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ApiKey findOwnedKey(UUID keyId, UUID merchantId) {
        ApiKey key = apiKeyRepository.findById(keyId)
            .orElseThrow(() -> new EbithexException(ErrorCode.API_KEY_NOT_FOUND, "Clé introuvable"));
        if (!key.getMerchantId().equals(merchantId)) {
            throw new EbithexException(ErrorCode.API_KEY_NOT_FOUND, "Clé introuvable");
        }
        if (!key.isActive()) {
            throw new EbithexException(ErrorCode.API_KEY_INACTIVE, "Cette clé est déjà révoquée");
        }
        return key;
    }

    private ApiKey buildKey(UUID merchantId, String rawKey, ApiKeyType type,
                            String label, Set<ApiKeyScope> scopes,
                            Set<String> allowedIps, LocalDateTime expiresAt) {
        String prefix = type == ApiKeyType.TEST ? "ap_test_" : "ap_live_";
        String scopeStr = (scopes == null || scopes.isEmpty())
            ? ApiKeyScope.FULL_ACCESS.name()
            : scopes.stream().map(ApiKeyScope::name).collect(Collectors.joining(","));
        String ipStr = (allowedIps == null || allowedIps.isEmpty())
            ? null
            : String.join(",", allowedIps);

        return ApiKey.builder()
            .merchantId(merchantId)
            .keyHash(sha256(rawKey))
            .keyHint(lastFour(rawKey))
            .prefix(prefix)
            .type(type)
            .label(label)
            .scopes(scopeStr)
            .allowedIps(ipStr)
            .expiresAt(expiresAt)
            .active(true)
            .build();
    }

    private String generateRawKey(ApiKeyType type) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String prefix = type == ApiKeyType.TEST ? "ap_test_" : "ap_live_";
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String lastFour(String rawKey) {
        return "..." + rawKey.substring(Math.max(0, rawKey.length() - 4));
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
