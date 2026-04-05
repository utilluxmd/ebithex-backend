package com.ebithex.webhook.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(length = 50)
    private String event;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Builder.Default
    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Builder.Default
    private boolean delivered = false;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Builder.Default
    @Column(name = "dead_lettered")
    private boolean deadLettered = false;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;
}
