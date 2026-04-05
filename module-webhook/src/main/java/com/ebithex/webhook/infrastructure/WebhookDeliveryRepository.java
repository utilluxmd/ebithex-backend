package com.ebithex.webhook.infrastructure;

import com.ebithex.webhook.domain.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    @Query("""
            SELECT d FROM WebhookDelivery d
            WHERE d.delivered = false
              AND d.deadLettered = false
              AND d.attemptCount < 5
              AND (d.nextRetryAt IS NULL OR d.nextRetryAt <= :now)
            ORDER BY d.nextRetryAt ASC NULLS FIRST
            """)
    List<WebhookDelivery> findPendingRetries(Instant now);

    @Query("""
            SELECT d FROM WebhookDelivery d
            WHERE d.deadLettered = true
            ORDER BY d.deadLetteredAt DESC
            """)
    List<WebhookDelivery> findDeadLettered();

    List<WebhookDelivery> findByTransactionId(UUID transactionId);

    Page<WebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId, Pageable pageable);
}
