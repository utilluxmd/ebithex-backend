package com.ebithex.shared.crypto;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration multi-clés pour la rotation AES-256-GCM.
 *
 * Exemple de configuration avec rotation vers la clé v2 :
 *
 *   # Clé active (chiffrement des nouvelles données)
 *   ebithex.security.encryption.active-version=2
 *   # Clé v1 (déchiffrement des anciens ciphertexts)
 *   ebithex.security.encryption.versions[1]=<base64-v1>
 *   # Clé v2 (chiffrement + déchiffrement)
 *   ebithex.security.encryption.versions[2]=<base64-v2>
 *
 *   # Rétrocompatibilité : si seule `key` est définie, elle devient la clé v1
 *   ebithex.security.encryption.key=<base64-v1>
 *
 * Format ciphertext en base :
 *   - Héritage (avant rotation) : Base64(IV || TAG+CIPHERTEXT)          → interprété comme v1
 *   - Nouveau format             : "v{N}:" + Base64(IV || TAG+CIPHERTEXT)
 */
@ConfigurationProperties(prefix = "ebithex.security.encryption")
@Component
@Getter
@Setter
public class EncryptionProperties {

    /** Clé de chiffrement principale (rétrocompatibilité — équivalent à versions[1]). */
    private String key;

    /** Version de clé utilisée pour les nouveaux chiffrements. Défaut : 1. */
    private int activeVersion = 1;

    /**
     * Map version → clé Base64 (32 bytes = 256 bits).
     * Exemple YAML : versions[1]: abc…, versions[2]: xyz…
     */
    private Map<Integer, String> versions = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        // Rétrocompatibilité : injecter `key` comme version 1 si versions[1] absent
        if (key != null && !key.isBlank() && !versions.containsKey(1)) {
            versions.put(1, key);
        }
        if (versions.isEmpty()) {
            throw new IllegalStateException(
                "Aucune clé de chiffrement configurée. " +
                "Définir ebithex.security.encryption.key ou ebithex.security.encryption.versions[1]");
        }
        if (!versions.containsKey(activeVersion)) {
            throw new IllegalStateException(
                "La version active " + activeVersion + " n'a pas de clé configurée dans versions[]");
        }
    }
}