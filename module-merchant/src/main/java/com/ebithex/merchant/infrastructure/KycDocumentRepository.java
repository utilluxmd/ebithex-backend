package com.ebithex.merchant.infrastructure;

import com.ebithex.merchant.domain.KycDocument;
import com.ebithex.merchant.domain.KycDocumentStatus;
import com.ebithex.merchant.domain.KycDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    /** All active (non-deleted) documents for a merchant. */
    @Query("SELECT d FROM KycDocument d WHERE d.merchantId = :merchantId AND d.deletedAt IS NULL ORDER BY d.uploadedAt DESC")
    List<KycDocument> findActiveByMerchantId(@Param("merchantId") UUID merchantId);

    /** Active documents of a given type for a merchant. */
    @Query("SELECT d FROM KycDocument d WHERE d.merchantId = :merchantId AND d.documentType = :type AND d.deletedAt IS NULL ORDER BY d.uploadedAt DESC")
    List<KycDocument> findActiveByMerchantIdAndType(@Param("merchantId") UUID merchantId,
                                                     @Param("type") KycDocumentType type);

    /** Documents pending back-office review across all merchants. */
    @Query("SELECT d FROM KycDocument d WHERE d.status = :status AND d.deletedAt IS NULL ORDER BY d.uploadedAt ASC")
    List<KycDocument> findByStatus(@Param("status") KycDocumentStatus status);

    /** Find non-deleted document by id (security: must also belong to the merchant). */
    @Query("SELECT d FROM KycDocument d WHERE d.id = :id AND d.merchantId = :merchantId AND d.deletedAt IS NULL")
    Optional<KycDocument> findActiveByIdAndMerchantId(@Param("id") UUID id,
                                                       @Param("merchantId") UUID merchantId);

    /** Check for duplicate uploads (same file content). */
    boolean existsByMerchantIdAndChecksumSha256AndDeletedAtIsNull(UUID merchantId, String checksum);
}
