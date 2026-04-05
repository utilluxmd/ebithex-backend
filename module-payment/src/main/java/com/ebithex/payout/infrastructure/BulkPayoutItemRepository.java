package com.ebithex.payout.infrastructure;

import com.ebithex.payout.domain.BulkPayoutItem;
import com.ebithex.shared.domain.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BulkPayoutItemRepository extends JpaRepository<BulkPayoutItem, UUID> {
    List<BulkPayoutItem> findByBulkPayoutIdOrderByItemIndex(UUID bulkPayoutId);
    long countByBulkPayoutIdAndStatus(UUID bulkPayoutId, TransactionStatus status);
}
