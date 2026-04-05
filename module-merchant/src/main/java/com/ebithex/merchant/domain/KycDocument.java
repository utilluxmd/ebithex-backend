package com.ebithex.merchant.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private KycDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private KycDocumentStatus status = KycDocumentStatus.UPLOADED;

    /** Object key in the storage bucket (never exposed to clients). */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /** Original filename as supplied by the merchant. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    /** SHA-256 hex of the raw bytes — used for deduplication and integrity checks. */
    @Column(name = "checksum_sha256", nullable = false)
    private String checksumSha256;

    /** KYC provider that verified this document (nullable if manual review). */
    @Column(name = "provider_name")
    private String providerName;

    /** Provider's own reference / job ID. */
    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "reviewer_notes")
    private String reviewerNotes;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant uploadedAt = Instant.now();

    /** Document expiry date (e.g. passport). Null = no known expiry. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Soft-delete timestamp. Null means the record is active. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}