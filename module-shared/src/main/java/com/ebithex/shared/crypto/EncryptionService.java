package com.ebithex.shared.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Chiffrement symétrique AES-256-GCM avec support de rotation de clés.
 *
 * <h3>Format des ciphertexts</h3>
 * <ul>
 *   <li><b>Héritage</b> (avant rotation) : {@code Base64(IV[12] || TAG+CIPHERTEXT)} — interprété comme version 1</li>
 *   <li><b>Nouveau</b> : {@code "v{N}:" + Base64(IV[12] || TAG+CIPHERTEXT)}</li>
 * </ul>
 *
 * <h3>Rotation de clés</h3>
 * <ol>
 *   <li>Générer une nouvelle clé 256 bits et l'ajouter dans {@code ebithex.security.encryption.versions[N]}</li>
 *   <li>Définir {@code ebithex.security.encryption.active-version=N}</li>
 *   <li>Appeler {@code POST /internal/admin/key-rotation} pour re-chiffrer les enregistrements existants</li>
 *   <li>Après confirmation, supprimer les anciennes versions de la configuration</li>
 * </ol>
 *
 * <h3>Index HMAC</h3>
 * {@link #hmacForIndex} utilise toujours la clé v1 pour garantir la stabilité de l'index de recherche
 * même après rotation. L'index n'est PAS re-calculé lors d'une rotation.
 */
@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN   = 12;   // bytes
    private static final int    GCM_TAG_BITS = 128;  // bits
    private static final String VERSION_PREFIX = "v";

    private final Map<Integer, SecretKey> keysByVersion;
    private final int                     activeVersion;
    private final SecureRandom            secureRandom = new SecureRandom();

    public EncryptionService(EncryptionProperties props) {
        this.activeVersion = props.getActiveVersion();
        this.keysByVersion = new HashMap<>();

        props.getVersions().forEach((version, base64Key) -> {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                    "Clé version " + version + " : doit être 256 bits (32 bytes). Reçu: " + keyBytes.length);
            }
            keysByVersion.put(version, new SecretKeySpec(keyBytes, "AES"));
        });

        log.info("EncryptionService initialisé: {} version(s) de clé, version active={}",
            keysByVersion.size(), activeVersion);
    }

    // ── Chiffrement ───────────────────────────────────────────────────────────

    /**
     * Chiffre une valeur en clair avec la clé active.
     *
     * @return ciphertext préfixé {@code "v{N}:Base64(IV || TAG+CIPHERTEXT)"}, ou null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            SecretKey key = getKey(activeVersion);
            byte[] iv = new byte[GCM_IV_LEN];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return VERSION_PREFIX + activeVersion + ":" + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Échec du chiffrement", e);
        }
    }

    // ── Déchiffrement ─────────────────────────────────────────────────────────

    /**
     * Déchiffre un ciphertext, quelle que soit sa version.
     * Supporte les ciphertexts hérités (sans préfixe de version — traités comme v1).
     *
     * @return texte en clair, ou null si l'entrée est null
     */
    public String decrypt(String encryptedValue) {
        if (encryptedValue == null) return null;

        int version;
        String base64Part;

        if (encryptedValue.startsWith(VERSION_PREFIX) && encryptedValue.indexOf(':') > 1) {
            // Format versionné : "v{N}:Base64..."
            int colonIdx = encryptedValue.indexOf(':');
            try {
                version = Integer.parseInt(encryptedValue, 1, colonIdx, 10);
                base64Part = encryptedValue.substring(colonIdx + 1);
            } catch (NumberFormatException e) {
                // Préfixe non reconnu — traiter comme héritage v1
                version = 1;
                base64Part = encryptedValue;
            }
        } else {
            // Format héritage : pas de préfixe → v1
            version = 1;
            base64Part = encryptedValue;
        }

        return decryptWithVersion(base64Part, version);
    }

    /**
     * Retourne la version de clé intégrée dans un ciphertext.
     * Retourne 1 pour les ciphertexts hérités sans préfixe.
     */
    public int getVersion(String encryptedValue) {
        if (encryptedValue == null) return -1;
        if (encryptedValue.startsWith(VERSION_PREFIX) && encryptedValue.indexOf(':') > 1) {
            int colonIdx = encryptedValue.indexOf(':');
            try {
                return Integer.parseInt(encryptedValue, 1, colonIdx, 10);
            } catch (NumberFormatException ignored) {}
        }
        return 1; // héritage
    }

    /** Retourne la version de clé active (utilisée pour les nouveaux chiffrements). */
    public int getActiveVersion() {
        return activeVersion;
    }

    // ── Index HMAC (stable — toujours clé v1) ────────────────────────────────

    /**
     * Retourne un HMAC-SHA256 déterministe de la valeur (pour indexation/recherche en base).
     * Utilise toujours la clé v1 pour garantir la stabilité de l'index lors des rotations de clé.
     */
    public String hmacForIndex(String value) {
        if (value == null) return null;
        try {
            SecretKey hmacKey = getKey(1);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] hash = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Échec du HMAC", e);
        }
    }

    // ── Helpers privés ────────────────────────────────────────────────────────

    private String decryptWithVersion(String base64Part, int version) {
        try {
            SecretKey key = getKey(version);
            byte[] decoded = Base64.getDecoder().decode(base64Part);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LEN];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Échec du déchiffrement (version={}) — données corrompues ou clé incorrecte", version);
            throw new IllegalStateException("Échec du déchiffrement", e);
        }
    }

    private SecretKey getKey(int version) {
        SecretKey key = keysByVersion.get(version);
        if (key == null) {
            throw new IllegalStateException(
                "Clé de chiffrement version " + version + " non configurée. " +
                "Vérifier ebithex.security.encryption.versions[" + version + "]");
        }
        return key;
    }
}