package com.ebithex.merchant.domain;

import com.ebithex.shared.apikey.ApiKeyScope;
import com.ebithex.shared.apikey.ApiKeyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Clé API d'un marchand.
 *
 * <p>Chaque marchand possède au minimum deux clés créées à l'inscription :
 * une clé live ({@code ap_live_}) et une clé sandbox ({@code ap_test_}),
 * toutes deux avec le scope {@code FULL_ACCESS}.
 *
 * <p>Le marchand peut créer des clés supplémentaires avec des scopes restreints,
 * une restriction IP, et/ou une date d'expiration.
 *
 * <p>La valeur brute de la clé n'est jamais persistée — seul son SHA-256 est stocké.
 * La valeur brute est retournée une seule fois à la création ou à la rotation.
 *
 * <p><b>Rotation</b> : la rotation crée un nouvel enregistrement et désactive l'ancien.
 * Le nouveau enregistrement porte {@code previousHash} = hash de l'ancien, valide
 * pendant {@code previousExpiresAt} (période de grâce, défaut 24h).
 */
@Entity
@Table(name = "api_keys", indexes = {
    @Index(name = "idx_api_keys_merchant", columnList = "merchant_id"),
    @Index(name = "idx_api_keys_hash",     columnList = "key_hash")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** SHA-256 hex de la clé courante — jamais stockée en clair. */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    /** 4 derniers caractères de la clé brute — pour affichage (ex. "...xK3a"). */
    @Column(name = "key_hint", nullable = false, length = 8)
    private String keyHint;

    /** Préfixe de la clé ("ap_live_" ou "ap_test_") — pour affichage. */
    @Column(name = "prefix", nullable = false, length = 20)
    private String prefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApiKeyType type;

    /** Libellé optionnel défini par le marchand (ex. "Intégration Shopify"). */
    @Column(length = 100)
    private String label;

    /**
     * Scopes autorisés, stockés comme chaîne comma-separated.
     * Ex : "PAYMENTS_WRITE,PAYMENTS_READ" ou "FULL_ACCESS"
     */
    @Column(nullable = false)
    @Builder.Default
    private String scopes = ApiKeyScope.FULL_ACCESS.name();

    /**
     * IPs autorisées, comma-separated.
     * NULL = aucune restriction IP.
     * Ex : "41.202.15.1,41.202.15.2"
     */
    @Column(name = "allowed_ips")
    private String allowedIps;

    /** Date d'expiration. NULL = pas d'expiration. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Dernière utilisation — mise à jour par ApiKeyAuthFilter. */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // ── Grace period (champs du nouveau enregistrement après rotation) ────────

    /**
     * SHA-256 de l'ancienne clé, accepté jusqu'à {@code previousExpiresAt}.
     * Renseigné uniquement sur le nouvel enregistrement créé lors d'une rotation.
     */
    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "previous_expires_at")
    private LocalDateTime previousExpiresAt;

    // ── Aging ─────────────────────────────────────────────────────────────────

    /** Horodatage du dernier rappel de rotation envoyé au marchand. */
    @Column(name = "aging_reminder_sent_at")
    private LocalDateTime agingReminderSentAt;

    /**
     * Nombre de jours avant rotation obligatoire, configuré par SUPER_ADMIN.
     * NULL = pas de rotation forcée.
     */
    @Column(name = "rotation_required_days")
    private Integer rotationRequiredDays;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Parse la chaîne scopes en Set<ApiKeyScope>. */
    public Set<ApiKeyScope> parsedScopes() {
        return Arrays.stream(scopes.split(","))
            .map(String::trim)
            .map(ApiKeyScope::valueOf)
            .collect(Collectors.toSet());
    }

    /** Indique si la clé est expirée (date d'expiration dépassée). */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    /**
     * Indique si l'IP donnée est autorisée à utiliser cette clé.
     * Retourne true si aucune restriction IP n'est configurée.
     */
    public boolean isIpAllowed(String clientIp) {
        if (allowedIps == null || allowedIps.isBlank()) return true;
        return Arrays.stream(allowedIps.split(","))
            .map(String::trim)
            .anyMatch(ip -> ip.equals(clientIp));
    }
}
