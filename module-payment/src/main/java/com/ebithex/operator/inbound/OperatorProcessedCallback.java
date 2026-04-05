package com.ebithex.operator.inbound;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Enregistre chaque callback opérateur traité.
 * La contrainte UNIQUE(operator, operator_reference) garantit l'idempotence.
 */
@Entity
@Table(
    name = "operator_processed_callbacks",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_operator_callback",
        columnNames = {"operator", "operator_reference"}
    )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperatorProcessedCallback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String operator;

    @Column(name = "operator_reference", nullable = false, length = 255)
    private String operatorReference;

    @CreationTimestamp
    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;
}
