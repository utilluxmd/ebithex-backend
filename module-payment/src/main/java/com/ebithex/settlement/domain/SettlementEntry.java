package com.ebithex.settlement.domain;

import com.ebithex.shared.domain.OperatorType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Ligne individuelle d'un lot de règlement (1 entrée = 1 transaction). */
@Entity
@Table(name = "settlement_entries", indexes = {
    @Index(name = "idx_settlement_entry_batch", columnList = "batch_id"),
    @Index(name = "idx_settlement_entry_tx",    columnList = "transactionId")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SettlementEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementEntryType entryType;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(precision = 15, scale = 2)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OperatorType operator;

    @Column(nullable = false, length = 5)
    private String currency;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
