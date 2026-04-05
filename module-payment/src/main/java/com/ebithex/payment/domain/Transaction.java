package com.ebithex.payment.domain;

import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import jakarta.persistence.*;
// phoneNumber stocké chiffré (AES-256-GCM) — déchiffré à la lecture via PaymentService
// phoneNumberIndex = HMAC-SHA256 du numéro normalisé — pour les requêtes filtrées par téléphone
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Filtre Hibernate activé sur les requêtes marchands pour garantir
 * l'isolation des données. Activé via {@code MerchantFilterAspect}
 * sur les appels de service authentifiés par un principal marchand.
 * Les requêtes admin/batch ne l'activent pas (accès cross-marchands légitime).
 * La définition du filtre ({@code @FilterDef}) est dans {@code package-info.java}.
 */
@Filter(name = "merchantFilter", condition = "merchant_id = CAST(:merchantId AS uuid)")
@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_tx_merchant",     columnList = "merchant_id"),
    @Index(name = "idx_tx_operator_ref", columnList = "operatorReference"),
    @Index(name = "idx_tx_status",       columnList = "status"),
    @Index(name = "idx_tx_phone",        columnList = "phoneNumber"),
    @Index(name = "idx_tx_created",      columnList = "createdAt")
    // idx_tx_test removed — sandbox isolation is now at the schema level
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String ebithexReference;

    @Column(nullable = false)
    private String merchantReference;

    /** Cross-module reference to merchant — stored as plain UUID, no @ManyToOne */
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(precision = 15, scale = 2)
    private BigDecimal feeAmount;

    @Column(precision = 15, scale = 2)
    private BigDecimal netAmount;

    @Enumerated(EnumType.STRING)
    private Currency currency = Currency.XOF;

    /** Numéro de téléphone chiffré AES-256-GCM (valeur brute jamais stockée en clair). */
    @Column(name = "phone_number", nullable = false, columnDefinition = "TEXT")
    private String phoneNumber;

    /**
     * HMAC-SHA256 du numéro normalisé E.164 — permet les requêtes filtrées
     * par numéro sans stocker en clair. Indexé.
     */
    @Column(name = "phone_number_index", length = 64)
    private String phoneNumberIndex;

    @Enumerated(EnumType.STRING)
    private OperatorType operator;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status = TransactionStatus.PENDING;

    private String operatorReference;

    /** Référence attribuée par l'opérateur lors du remboursement (reversal). */
    private String operatorRefundReference;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    private String customerName;
    private String customerEmail;
    private String description;
    private String failureReason;
    private LocalDateTime expiresAt;

    /**
     * Montant cumulatif déjà remboursé (remboursements partiels successifs).
     * Null si aucun remboursement n'a encore été effectué.
     * Quand refundedAmount == amount, le statut passe à REFUNDED.
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal refundedAmount;

    /**
     * Date de purge des données PII (phone_number anonymisé, phone_number_index effacé).
     * Null tant que les données sont intactes.
     */
    private LocalDateTime piiPurgedAt;

    /**
     * Version pour le verrouillage optimiste JPA.
     * Prévient les mises à jour concurrentes silencieuses (ex : callback opérateur
     * + job d'expiration modifiant le statut simultanément).
     * Une {@code OptimisticLockException} est levée si deux transactions lisent
     * la même version et que la seconde tente un flush.
     */
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
