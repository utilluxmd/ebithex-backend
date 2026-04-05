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
 * Portefeuille d'un marchand dans une devise donnée.
 *
 * Un marchand peut avoir plusieurs wallets — un par devise active.
 * Le wallet est créé automatiquement lors du premier paiement dans une devise.
 *
 * availableBalance : fonds disponibles (retrait possible)
 * pendingBalance   : fonds bloqués par des décaissements en cours
 *
 * @Version pour verrouillage optimiste — prévient les corruptions de balance
 * en cas d'événements concurrents sur le même (marchand, devise).
 */
@Entity
@Table(name = "wallets",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_wallet_merchant_currency",
           columnNames = {"merchant_id", "currency"}))
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "available_balance", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Column(name = "pending_balance", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal pendingBalance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    @Builder.Default
    private Currency currency = Currency.XOF;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}