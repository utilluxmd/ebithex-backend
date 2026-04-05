package com.ebithex.merchant.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.time.Duration;

@Slf4j
public class S3StorageService implements StorageService {

    private final S3Client   s3Client;
    private final S3Presigner presigner;
    private final String     bucket;

    public S3StorageService(S3Client s3Client, S3Presigner presigner, String bucket) {
        this.s3Client  = s3Client;
        this.presigner = presigner;
        this.bucket    = bucket;
    }

    @Override
    public void store(String key, InputStream data, String contentType, long sizeBytes) {
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .serverSideEncryption("AES256")
            .build();
        s3Client.putObject(request, RequestBody.fromInputStream(data, sizeBytes));
        log.debug("Stored KYC document: bucket={} key={}", bucket, key);
    }

    @Override
    public String presignedUrl(String key, Duration ttl) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(r -> r.bucket(bucket).key(key))
            .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
        return presigned.url().toString();
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build());
        log.debug("Deleted KYC document: bucket={} key={}", bucket, key);
    }
}
