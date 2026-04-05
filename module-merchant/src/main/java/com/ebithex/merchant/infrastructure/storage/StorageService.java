package com.ebithex.merchant.infrastructure.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * Abstraction over object-storage backends (S3/R2, local filesystem…).
 * Implementations must be thread-safe.
 */
public interface StorageService {

    /**
     * Stores {@code data} under the given key.
     *
     * @param key         path/key within the bucket
     * @param data        raw bytes
     * @param contentType MIME type
     * @param sizeBytes   exact byte count (required by S3 SDK for streaming uploads)
     */
    void store(String key, InputStream data, String contentType, long sizeBytes);

    /**
     * Returns a pre-signed URL that allows the bearer to download the object for the
     * given duration without any authentication headers.
     *
     * @param key object key
     * @param ttl validity period (≤ 7 days for AWS SigV4)
     * @return pre-signed URL string
     */
    String presignedUrl(String key, Duration ttl);

    /**
     * Permanently deletes an object from storage.
     * Idempotent — no error if key does not exist.
     */
    void delete(String key);
}
