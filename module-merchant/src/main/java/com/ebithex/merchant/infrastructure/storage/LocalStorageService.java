package com.ebithex.merchant.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Filesystem-backed storage for local development.
 * NOT suitable for production — no redundancy, no CDN, no server-side encryption.
 *
 * Pre-signed URLs are faked with a time-limited token stored in memory (JVM restart
 * invalidates them — acceptable for dev use only).
 */
@Slf4j
public class LocalStorageService implements StorageService {

    private final Path basePath;
    private final String baseUrl;

    /** In-memory token → (key, expiry). Only for local dev. */
    private final java.util.concurrent.ConcurrentHashMap<String, TokenEntry> tokens =
        new java.util.concurrent.ConcurrentHashMap<>();

    public LocalStorageService(Path basePath, String baseUrl) {
        this.basePath = basePath;
        this.baseUrl  = baseUrl;
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create local storage dir: " + basePath, e);
        }
    }

    @Override
    public void store(String key, InputStream data, String contentType, long sizeBytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.debug("LocalStorage: stored key={}", key);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store KYC document locally", e);
        }
    }

    @Override
    public String presignedUrl(String key, Duration ttl) {
        String token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(UUID.randomUUID().toString().getBytes());
        tokens.put(token, new TokenEntry(key, Instant.now().plus(ttl)));
        return baseUrl + "/internal/kyc/local-download?token=" + token;
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
            log.debug("LocalStorage: deleted key={}", key);
        } catch (IOException e) {
            log.warn("LocalStorage: could not delete key={}", key, e);
        }
    }

    public Path resolve(String key) {
        return basePath.resolve(key.replace('/', Path.of("/").getFileSystem().getSeparator().charAt(0)));
    }

    public boolean isTokenValid(String token, String[] keyOut) {
        TokenEntry entry = tokens.get(token);
        if (entry == null || Instant.now().isAfter(entry.expiry())) {
            tokens.remove(token);
            return false;
        }
        keyOut[0] = entry.key();
        return true;
    }

    private record TokenEntry(String key, Instant expiry) {}
}
