package com.ebithex.shared.outbox;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox Pattern — garantit la livraison des événements domaine.
 * Écrit dans la MÊME transaction que le changement d'état, puis lu par OutboxPoller.
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = @Index(name = "idx_outbox_pending", columnList = "status, created_at")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Type d'agrégat : ex. "Transaction" */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /** UUID de l'agrégat source */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** Nom complet de l'événement : ex. "PaymentStatusChangedEvent" */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Payload JSON sérialisé */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;
}
