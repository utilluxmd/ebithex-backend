package com.ebithex.wallet.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Grand livre immuable des mouvements du wallet.
 * Chaque ligne correspond à un crédit ou débit sur la balance du marchand.
 *
 * La contrainte unique (ebithex_reference, type) garantit l'idempotence :
 * un même événement rejoué ne crée pas de double mouvement.
 */
@Entity
@Table(name = "wallet_transactions",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_wallet_tx_ref_type",
           columnNames = {"ebithex_reference", "type"}))
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WalletTransactionType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "ebithex_reference", nullable = false, length = 50)
    private String ebithexReference;

    @Column(length = 255)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}