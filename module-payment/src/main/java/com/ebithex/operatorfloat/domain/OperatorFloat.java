package com.ebithex.operatorfloat.domain;

import com.ebithex.shared.domain.OperatorType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Float Ebithex par opérateur Mobile Money.
 *
 * Représente le solde de trésorerie qu'Ebithex détient chez chaque opérateur.
 *   - Crédité lors d'une collecte (payment SUCCESS)
 *   - Débité lors d'un décaissement (payout initié)
 *   - Recrédité si le décaissement échoue (payout FAILED)
 *
 * Le verrou optimiste (@Version) protège contre les concurrences sur la balance.
 */
@Entity
@Table(name = "operator_floats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorFloat {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "operator_type", length = 30)
    private OperatorType operatorType;

    @Column(name = "balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(name = "low_balance_threshold", nullable = false, precision = 18, scale = 2)
    private BigDecimal lowBalanceThreshold;

    @Version
    private Long version;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}