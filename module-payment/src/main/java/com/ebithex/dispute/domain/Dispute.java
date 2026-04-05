package com.ebithex.dispute.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Litige ouvert par un marchand sur une transaction.
 *
 * Cycle de vie :
 *  OPEN → UNDER_REVIEW → RESOLVED_MERCHANT (résolu en faveur du marchand)
 *                      → RESOLVED_CUSTOMER (résolu en faveur du client)
 *       → CANCELLED    (litige retiré par le marchand)
 */
@Entity
@Table(name = "disputes", indexes = {
    @Index(name = "idx_dispute_merchant",    columnList = "merchant_id"),
    @Index(name = "idx_dispute_status",      columnList = "status, openedAt"),
    @Index(name = "idx_dispute_ebithex_ref", columnList = "ebithexReference")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String ebithexReference;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DisputeReason reason;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 5)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DisputeStatus status = DisputeStatus.OPEN;

    @CreationTimestamp
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    private LocalDateTime resolvedAt;

    @Column(length = 100)
    private String resolvedBy;

    @Column(columnDefinition = "TEXT")
    private String resolutionNotes;

    /** JSON array d'URLs de preuves téléversées. */
    @Column(columnDefinition = "TEXT")
    private String evidenceUrls;
}