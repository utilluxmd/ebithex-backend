package com.ebithex.payout.domain;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Représente un décaissement (payout / cash-out) : Ebithex envoie des fonds
 * du solde marchand vers un portefeuille Mobile Money bénéficiaire.
 *
 * Le numéro de téléphone est chiffré AES-256-GCM (identique à Transaction).
 */
@Filter(name = "merchantFilter", condition = "merchant_id = CAST(:merchantId AS uuid)")
@Entity
@Table(name = "payouts", indexes = {
    @Index(name = "idx_po_merchant",     columnList = "merchant_id"),
    @Index(name = "idx_po_operator_ref", columnList = "operatorReference"),
    @Index(name = "idx_po_status",       columnList = "status"),
    @Index(name = "idx_po_phone",        columnList = "phoneNumberIndex"),
    @Index(name = "idx_po_created",      columnList = "createdAt")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Référence unique Ebithex — format PO-YYYYMMDD-XXXXX. */
    @Column(unique = true, nullable = false)
    private String ebithexReference;

    @Column(nullable = false)
    private String merchantReference;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(precision = 15, scale = 2)
    private BigDecimal feeAmount;

    /** Montant net reçu par le bénéficiaire = amount - feeAmount. */
    @Column(precision = 15, scale = 2)
    private BigDecimal netAmount;

    @Enumerated(EnumType.STRING)
    private Currency currency = Currency.XOF;

    /** Numéro bénéficiaire chiffré AES-256-GCM. */
    @Column(name = "phone_number", nullable = false, columnDefinition = "TEXT")
    private String phoneNumber;

    /** HMAC-SHA256 du numéro normalisé — pour les requêtes filtrées. */
    @Column(name = "phone_number_index", length = 64)
    private String phoneNumberIndex;

    @Enumerated(EnumType.STRING)
    private OperatorType operator;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status = TransactionStatus.PENDING;

    private String operatorReference;

    @Column(length = 255)
    private String description;

    private String beneficiaryName;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    private String failureReason;
    private LocalDateTime expiresAt;

    /** Date de purge des données PII (phone_number anonymisé). Null tant que intact. */
    private LocalDateTime piiPurgedAt;

    /** Version pour le verrouillage optimiste JPA. */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
