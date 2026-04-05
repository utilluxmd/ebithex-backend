package com.ebithex.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = 'PENDING'
        ORDER BY e.createdAt ASC
        LIMIT 100
        """)
    List<OutboxEvent> findPendingBatch();

    @Modifying
    @Query("""
        DELETE FROM OutboxEvent e
        WHERE e.status IN ('DISPATCHED', 'FAILED')
          AND e.createdAt < :cutoff
        """)
    int deleteOldProcessedEvents(@Param("cutoff") Instant cutoff);
}
