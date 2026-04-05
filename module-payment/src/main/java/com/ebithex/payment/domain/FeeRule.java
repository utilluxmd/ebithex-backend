package com.ebithex.payment.domain;

import com.ebithex.shared.domain.OperatorType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Règle tarifaire dynamique.
 *
 * Priorité de résolution (la première règle active correspondante est utilisée) :
 *  1. merchant_id + operator  → règle spécifique au marchand pour cet opérateur
 *  2. merchant_id seul        → règle spécifique au marchand (tous opérateurs)
 *  3. operator seul           → règle par opérateur (tous marchands)
 *  4. country seul            → règle par pays
 *  5. tout null               → règle globale (fallback)
 *
 * Pour chaque niveau, c'est la règle avec le priority le plus élevé qui gagne.
 */
@Entity
@Table(name = "fee_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeeRule {

    public enum FeeType { PERCENTAGE, FLAT, MIXED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // ── Ciblage ──────────────────────────────────────────────────────────────

    /** null = s'applique à tous les marchands */
    @Column(name = "merchant_id")
    private UUID merchantId;

    /** null = s'applique à tous les opérateurs */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OperatorType operator;

    /** null = s'applique à tous les pays */
    @Column(length = 3)
    private String country;

    // ── Structure tarifaire ──────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_type", nullable = false, length = 15)
    @Builder.Default
    private FeeType feeType = FeeType.PERCENTAGE;

    /** Taux en pourcentage, ex : 1.5000 = 1.5% */
    @Column(name = "percentage_rate", precision = 6, scale = 4)
    private BigDecimal percentageRate;

    /** Montant fixe en devise locale */
    @Column(name = "flat_amount", precision = 15, scale = 2)
    private BigDecimal flatAmount;

    /** Plancher des frais calculés */
    @Column(name = "min_fee", precision = 15, scale = 2)
    private BigDecimal minFee;

    /** Plafond des frais calculés */
    @Column(name = "max_fee", precision = 15, scale = 2)
    private BigDecimal maxFee;

    // ── Applicabilité ────────────────────────────────────────────────────────

    @Column(name = "min_amount", precision = 15, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 15, scale = 2)
    private BigDecimal maxAmount;

    // ── Priorité et validité ─────────────────────────────────────────────────

    @Builder.Default
    private int priority = 0;

    @Builder.Default
    private boolean active = true;

    @Column(name = "valid_from")
    @Builder.Default
    private LocalDateTime validFrom = LocalDateTime.now();

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Vérifie si la règle est active et dans sa fenêtre de validité */
    public boolean isEffective() {
        if (!active) return false;
        LocalDateTime now = LocalDateTime.now();
        if (validFrom != null && now.isBefore(validFrom)) return false;
        if (validUntil != null && now.isAfter(validUntil)) return false;
        return true;
    }

    /** Vérifie si la règle s'applique au montant donné */
    public boolean appliesTo(BigDecimal amount) {
        if (minAmount != null && amount.compareTo(minAmount) < 0) return false;
        if (maxAmount != null && amount.compareTo(maxAmount) > 0) return false;
        return true;
    }
}