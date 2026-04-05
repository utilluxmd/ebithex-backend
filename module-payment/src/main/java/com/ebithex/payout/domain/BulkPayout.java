package com.ebithex.payout.domain;

import com.ebithex.shared.domain.BulkPaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lot de décaissements (bulk cash-out) : le marchand envoie des fonds
 * vers plusieurs bénéficiaires Mobile Money en une seule requête API.
 *
 * Use cases : masse salariale, remboursements groupés, distribution d'aides.
 */
@Entity
@Table(name = "bulk_payouts", indexes = {
    @Index(name = "idx_bpo_merchant", columnList = "merchant_id"),
    @Index(name = "idx_bpo_status",   columnList = "status"),
    @Index(name = "idx_bpo_created",  columnList = "createdAt")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BulkPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Référence unique Ebithex — format BP-YYYYMMDD-XXXXX. */
    @Column(unique = true, nullable = false)
    private String ebithexBatchReference;

    @Column(nullable = false)
    private String merchantBatchReference;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(length = 255)
    private String label;

    private int totalItems;
    private int processedItems;
    private int successItems;
    private int failedItems;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private BulkPaymentStatus status = BulkPaymentStatus.PENDING;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
