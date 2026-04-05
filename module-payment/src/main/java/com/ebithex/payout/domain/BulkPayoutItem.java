package com.ebithex.payout.domain;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un bénéficiaire individuel dans un lot de décaissements.
 * Référence le Payout créé lors du traitement via payoutId.
 */
@Entity
@Table(name = "bulk_payout_items", indexes = {
    @Index(name = "idx_bpoi_batch",  columnList = "bulk_payout_id"),
    @Index(name = "idx_bpoi_payout", columnList = "payoutId"),
    @Index(name = "idx_bpoi_status", columnList = "status")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BulkPayoutItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bulk_payout_id", nullable = false)
    private UUID bulkPayoutId;

    /** Position dans le lot (0-based). */
    private int itemIndex;

    /** Référence marchand pour ce bénéficiaire — doit être unique par marchand. */
    @Column(nullable = false)
    private String merchantReference;

    /** Numéro bénéficiaire chiffré AES-256-GCM. */
    @Column(name = "phone_number", nullable = false, columnDefinition = "TEXT")
    private String phoneNumber;

    @Column(name = "phone_number_index", length = 64)
    private String phoneNumberIndex;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Currency currency = Currency.XOF;

    @Enumerated(EnumType.STRING)
    private OperatorType operator;

    @Column(length = 255)
    private String description;

    private String beneficiaryName;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    /** ID du Payout créé lors du traitement de cet item. */
    private UUID payoutId;

    /** Référence Ebithex du payout créé (ex: PO-20240315-X4K9M). */
    private String ebithexReference;

    private String failureReason;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
