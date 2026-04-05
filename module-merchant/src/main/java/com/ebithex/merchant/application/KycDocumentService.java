package com.ebithex.merchant.application;

import com.ebithex.merchant.domain.*;
import com.ebithex.merchant.dto.KycDocumentResponse;
import com.ebithex.merchant.dto.KycDocumentReviewRequest;
import com.ebithex.merchant.infrastructure.KycDocumentRepository;
import com.ebithex.merchant.infrastructure.storage.StorageService;
import com.ebithex.merchant.kyc.KycProvider;
import com.ebithex.merchant.kyc.KycProviderRegistry;
import com.ebithex.shared.audit.AuditLogService;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class KycDocumentService {

    private static final long   MAX_FILE_SIZE    = 10 * 1024 * 1024L; // 10 MB
    private static final Set<String> ALLOWED_MIME = Set.of(
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/webp"
    );
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final KycDocumentRepository documentRepository;
    private final StorageService        storageService;
    private final KycProviderRegistry   providerRegistry;
    private final MerchantService       merchantService;
    private final AuditLogService       auditLogService;

    @Value("${ebithex.kyc.auto-submit-to-provider:false}")
    private boolean autoSubmitToProvider;

    private final Tika tika = new Tika();

    // ── Upload ───────────────────────────────────────────────────────────────

    @Transactional
    public KycDocumentResponse upload(UUID merchantId, KycDocumentType documentType,
                                      MultipartFile file) {
        Merchant merchant = merchantService.findById(merchantId);

        validateFile(file);

        byte[] bytes = readBytes(file);
        String mimeType   = detectMimeType(bytes, file.getOriginalFilename());
        String checksum   = sha256(bytes);

        if (documentRepository.existsByMerchantIdAndChecksumSha256AndDeletedAtIsNull(merchantId, checksum)) {
            throw new EbithexException(ErrorCode.DUPLICATE_DOCUMENT, "Ce document a déjà été téléversé");
        }

        String storageKey = buildStorageKey(merchantId, documentType);

        try (InputStream stream = file.getInputStream()) {
            storageService.store(storageKey, stream, mimeType, bytes.length);
        } catch (IOException e) {
            throw new EbithexException(ErrorCode.STORAGE_ERROR, "Impossible de stocker le document");
        }

        KycDocument doc = KycDocument.builder()
            .merchantId(merchantId)
            .documentType(documentType)
            .status(KycDocumentStatus.UPLOADED)
            .storageKey(storageKey)
            .fileName(sanitizeFileName(file.getOriginalFilename()))
            .contentType(mimeType)
            .fileSizeBytes(bytes.length)
            .checksumSha256(checksum)
            .build();

        documentRepository.save(doc);
        log.info("KYC document uploaded: merchant={} type={} doc={}",
            merchantId, documentType, doc.getId());
        auditLogService.record("KYC_DOCUMENT_UPLOADED", "KycDocument", doc.getId().toString(),
            "{\"type\":\"" + documentType + "\",\"merchant\":\"" + merchantId + "\"}");

        if (autoSubmitToProvider) {
            submitToProvider(doc, merchant.getCountry());
        }

        return toResponse(doc);
    }

    // ── List ─────────────────────────────────────────────────────────────────

    public List<KycDocumentResponse> list(UUID merchantId) {
        return documentRepository.findActiveByMerchantId(merchantId)
            .stream().map(this::toResponse).toList();
    }

    // ── Presigned URL ────────────────────────────────────────────────────────

    public String presignedUrl(UUID merchantId, UUID documentId) {
        KycDocument doc = documentRepository.findActiveByIdAndMerchantId(documentId, merchantId)
            .orElseThrow(() -> new EbithexException(ErrorCode.DOCUMENT_NOT_FOUND, "Document introuvable"));
        return storageService.presignedUrl(doc.getStorageKey(), PRESIGN_TTL);
    }

    /** Admin-only presigned URL — no merchant ownership check. */
    public String adminPresignedUrl(UUID documentId) {
        KycDocument doc = documentRepository.findById(documentId)
            .filter(d -> !d.isDeleted())
            .orElseThrow(() -> new EbithexException(ErrorCode.DOCUMENT_NOT_FOUND, "Document introuvable"));
        return storageService.presignedUrl(doc.getStorageKey(), PRESIGN_TTL);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Transactional
    public void softDelete(UUID merchantId, UUID documentId) {
        KycDocument doc = documentRepository.findActiveByIdAndMerchantId(documentId, merchantId)
            .orElseThrow(() -> new EbithexException(ErrorCode.DOCUMENT_NOT_FOUND, "Document introuvable"));
        if (doc.getStatus() == KycDocumentStatus.ACCEPTED) {
            throw new EbithexException(ErrorCode.DOCUMENT_LOCKED, "Un document accepté ne peut pas être supprimé");
        }
        doc.setDeletedAt(Instant.now());
        documentRepository.save(doc);
        log.info("KYC document soft-deleted: doc={} merchant={}", documentId, merchantId);
    }

    // ── Back-office review ───────────────────────────────────────────────────

    @Transactional
    public KycDocumentResponse review(UUID documentId, KycDocumentReviewRequest req,
                                      String reviewerUsername) {
        KycDocument doc = documentRepository.findById(documentId)
            .filter(d -> !d.isDeleted())
            .orElseThrow(() -> new EbithexException(ErrorCode.DOCUMENT_NOT_FOUND, "Document introuvable"));

        KycDocumentStatus newStatus = req.status();
        if (newStatus != KycDocumentStatus.ACCEPTED && newStatus != KycDocumentStatus.REJECTED) {
            throw new EbithexException(ErrorCode.INVALID_STATUS, "Statut de révision invalide: " + newStatus);
        }

        doc.setStatus(newStatus);
        doc.setReviewerNotes(req.notes());
        doc.setReviewedBy(reviewerUsername);
        doc.setReviewedAt(Instant.now());
        documentRepository.save(doc);

        log.info("KYC document reviewed: doc={} status={} by={}", documentId, newStatus, reviewerUsername);
        auditLogService.record("KYC_DOCUMENT_" + newStatus.name(), "KycDocument", documentId.toString(),
            "{\"reviewer\":\"" + reviewerUsername + "\"}");

        return toResponse(doc);
    }

    /** Returns all documents for admin review (optionally filtered by status). */
    public List<KycDocumentResponse> listForReview(KycDocumentStatus status) {
        return documentRepository.findByStatus(status)
            .stream().map(this::toResponse).toList();
    }

    /** Returns all documents for a given merchant (admin view — includes all types). */
    public List<KycDocumentResponse> listForMerchant(UUID merchantId) {
        return documentRepository.findActiveByMerchantId(merchantId)
            .stream().map(this::toResponse).toList();
    }

    // ── Dossier completeness check ────────────────────────────────────────────

    /**
     * Returns true if all required document types have at least one ACCEPTED document.
     * Used by {@link MerchantService#submitKyc} to gate the submission.
     */
    public boolean isDossierComplete(UUID merchantId) {
        List<KycDocument> active = documentRepository.findActiveByMerchantId(merchantId);
        Set<KycDocumentType> required = KycDocumentType.REQUIRED;
        return required.stream().allMatch(type ->
            active.stream().anyMatch(d -> d.getDocumentType() == type
                && d.getStatus() == KycDocumentStatus.ACCEPTED));
    }

    // ── Provider submission ───────────────────────────────────────────────────

    @Transactional
    public void submitToProvider(KycDocument doc, String countryCode) {
        KycProvider provider = providerRegistry.forCountry(countryCode);
        try {
            doc.setStatus(KycDocumentStatus.PROCESSING);
            doc.setProviderName(provider.providerName());
            String ref = provider.submitVerification(doc, countryCode);
            doc.setProviderRef(ref);
            documentRepository.save(doc);
            log.info("Submitted doc={} to provider={} ref={}", doc.getId(), provider.providerName(), ref);
        } catch (Exception e) {
            log.error("Provider submission failed for doc={}: {}", doc.getId(), e.getMessage());
            doc.setStatus(KycDocumentStatus.UPLOADED); // revert to manual review
            documentRepository.save(doc);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new EbithexException(ErrorCode.EMPTY_FILE, "Fichier vide");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new EbithexException(ErrorCode.FILE_TOO_LARGE,
                "Le fichier dépasse la taille maximale autorisée (10 Mo)");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new EbithexException(ErrorCode.READ_ERROR, "Impossible de lire le fichier");
        }
    }

    private String detectMimeType(byte[] bytes, String originalFilename) {
        String detected = tika.detect(bytes, originalFilename != null ? originalFilename : "");
        if (!ALLOWED_MIME.contains(detected)) {
            throw new EbithexException(ErrorCode.INVALID_MIME_TYPE,
                "Type de fichier non autorisé: " + detected +
                ". Formats acceptés: PDF, JPEG, PNG, WEBP");
        }
        return detected;
    }

    private String buildStorageKey(UUID merchantId, KycDocumentType type) {
        return "kyc/" + merchantId + "/" + type.name().toLowerCase()
            + "/" + UUID.randomUUID() + ".bin";
    }

    private String sanitizeFileName(String original) {
        if (original == null) return "document";
        return original.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private KycDocumentResponse toResponse(KycDocument doc) {
        return new KycDocumentResponse(
            doc.getId(),
            doc.getDocumentType(),
            doc.getStatus(),
            doc.getFileName(),
            doc.getContentType(),
            doc.getFileSizeBytes(),
            doc.getProviderName(),
            doc.getReviewerNotes(),
            doc.getUploadedAt(),
            doc.getExpiresAt()
        );
    }
}
