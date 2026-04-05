package com.ebithex.dispute.infrastructure;

import com.ebithex.dispute.domain.Dispute;
import com.ebithex.dispute.domain.DisputeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    Page<Dispute> findByMerchantId(UUID merchantId, Pageable pageable);

    Optional<Dispute> findByEbithexReferenceAndMerchantId(String ebithexReference, UUID merchantId);

    @Query("SELECT d FROM Dispute d WHERE " +
        "(:status IS NULL OR d.status = :status) AND " +
        "(:merchantId IS NULL OR d.merchantId = :merchantId) AND " +
        "d.openedAt BETWEEN :from AND :to")
    Page<Dispute> findForBackOffice(
        @Param("status")     DisputeStatus status,
        @Param("merchantId") UUID merchantId,
        @Param("from")       LocalDateTime from,
        @Param("to")         LocalDateTime to,
        Pageable pageable);

    long countByMerchantIdAndStatus(UUID merchantId, DisputeStatus status);
}