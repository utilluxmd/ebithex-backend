package com.ebithex.payout.infrastructure;

import com.ebithex.payout.domain.BulkPayout;
import com.ebithex.shared.domain.BulkPaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BulkPayoutRepository extends JpaRepository<BulkPayout, UUID> {
    Optional<BulkPayout> findByEbithexBatchReference(String reference);
    Optional<BulkPayout> findByMerchantBatchReferenceAndMerchantId(String merchantBatchRef, UUID merchantId);
    Page<BulkPayout> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<BulkPayout> findByMerchantIdAndStatus(UUID merchantId, BulkPaymentStatus status, Pageable pageable);
}
