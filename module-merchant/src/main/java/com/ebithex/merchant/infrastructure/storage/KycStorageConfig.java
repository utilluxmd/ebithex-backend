package com.ebithex.merchant.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.nio.file.Path;

/**
 * Wires the correct {@link StorageService} implementation depending on the active profile.
 *
 * local/test → {@link LocalStorageService} (filesystem, no AWS credentials needed)
 * dev/prod   → {@link S3StorageService}   (S3-compatible: AWS S3, Cloudflare R2, MinIO)
 */
@Slf4j
@Configuration
public class KycStorageConfig {

    @Value("${ebithex.kyc.storage.provider:local}")
    private String provider;

    // S3 / R2 settings
    @Value("${ebithex.kyc.storage.s3.bucket:ebithex-kyc}")
    private String bucket;

    @Value("${ebithex.kyc.storage.s3.region:auto}")
    private String region;

    @Value("${ebithex.kyc.storage.s3.endpoint:}")
    private String endpoint;

    @Value("${ebithex.kyc.storage.s3.access-key:}")
    private String accessKey;

    @Value("${ebithex.kyc.storage.s3.secret-key:}")
    private String secretKey;

    // Local dev settings
    @Value("${ebithex.kyc.storage.local.path:${java.io.tmpdir}/ebithex-kyc}")
    private String localPath;

    @Value("${ebithex.app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    @Bean
    public StorageService kycStorageService() {
        if ("local".equalsIgnoreCase(provider)) {
            log.info("KYC storage: LOCAL filesystem at {}", localPath);
            return new LocalStorageService(Path.of(localPath), appBaseUrl);
        }

        log.info("KYC storage: S3-compatible — bucket={} region={} endpoint={}",
            bucket, region, endpoint.isBlank() ? "default" : endpoint);

        var credProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey));

        S3ClientBuilder clientBuilder = S3Client.builder()
            .credentialsProvider(credProvider)
            .region(Region.of(region));
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
            .credentialsProvider(credProvider)
            .region(Region.of(region));

        if (!endpoint.isBlank()) {
            URI endpointUri = URI.create(endpoint);
            clientBuilder.endpointOverride(endpointUri)
                         .forcePathStyle(true);
            presignerBuilder.endpointOverride(endpointUri);
        }

        return new S3StorageService(clientBuilder.build(), presignerBuilder.build(), bucket);
    }
}
