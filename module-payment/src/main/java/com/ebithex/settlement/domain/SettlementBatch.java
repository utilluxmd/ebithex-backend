package com.ebithex.settlement.domain;

import com.ebithex.shared.domain.OperatorType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lot de règlement quotidien Ebithex → opérateur.
 *
 * Un batch est créé par (opérateur, devise, période) et agrège toutes les
 * transactions SUCCESS de la période pour calculer le montant net dû.
 */
@Entity
@Table(name = "settlement_batches", indexes = {
    @Index(name = "idx_settlement_operator", columnList = "operator, periodStart"),
    @Index(name = "idx_settlement_status",   columnList = "status"),
    @Index(name = "idx_settlement_period",   columnList = "periodStart, periodEnd")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String batchReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OperatorType operator;

    @Column(nullable = false, length = 5)
    private String currency;

    @Column(nullable = false)
    private LocalDateTime periodStart;

    @Column(nullable = false)
    private LocalDateTime periodEnd;

    @Column(nullable = false)
    @Builder.Default
    private int transactionCount = 0;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SettlementBatchStatus status = SettlementBatchStatus.PENDING;

    private LocalDateTime settledAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
