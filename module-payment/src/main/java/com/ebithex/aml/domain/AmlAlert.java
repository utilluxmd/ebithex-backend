package com.ebithex.aml.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Alerte AML (Anti-Money Laundering) générée automatiquement par les règles de détection.
 *
 * Règles disponibles :
 *   VELOCITY_HOURLY   — > 10 transactions sur 1 heure
 *   VELOCITY_DAILY    — > 50 transactions sur 24 heures
 *   VELOCITY_WEEKLY   — > 200 transactions sur 7 jours
 *   HIGH_AMOUNT       — montant unitaire > seuil réglementaire
 *   STRUCTURING       — fractionnement pour passer sous le seuil de déclaration
 *   HIGH_RISK_COUNTRY — contrepartie dans un pays à risque FATF
 */
@Entity
@Table(name = "aml_alerts", indexes = {
    @Index(name = "idx_aml_merchant",    columnList = "merchant_id"),
    @Index(name = "idx_aml_status",      columnList = "status, createdAt"),
    @Index(name = "idx_aml_transaction", columnList = "transaction_id"),
    @Index(name = "idx_aml_rule",        columnList = "ruleCode")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AmlAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(nullable = false, length = 50)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AmlSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AmlStatus status = AmlStatus.OPEN;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 5)
    private String currency;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;

    @Column(length = 100)
    private String reviewedBy;

    @Column(columnDefinition = "TEXT")
    private String resolutionNote;
}