package com.ebithex.wallet.domain;

import com.ebithex.shared.domain.Currency;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Demande de retrait initiée par un marchand.
 *
 * Le workflow est :
 *   1. Marchand soumet → status = PENDING  (wallet NON débité)
 *   2. Finance approuve → status = APPROVED, wallet débité, transfert initié
 *      OU Finance rejette → status = REJECTED
 *   3. Finance confirme exécution → status = EXECUTED
 */
@Entity
@Table(name = "withdrawal_requests")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MerchantWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    @Builder.Default
    private Currency currency = Currency.XOF;

    @Column(nullable = false, unique = true, length = 50)
    private String reference;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WithdrawalStatus status = WithdrawalStatus.PENDING;

    /** UUID de l'opérateur back-office qui a traité la demande. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(length = 500)
    private String rejectionReason;

    private LocalDateTime reviewedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}