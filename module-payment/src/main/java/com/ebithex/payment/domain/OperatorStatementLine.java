package com.ebithex.payment.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ligne unitaire d'un relevé opérateur.
 *
 * Chaque ligne représente une transaction telle que rapportée par l'opérateur.
 * Après réconciliation, {@link #discrepancyType} indique si la ligne est concordante
 * avec notre système ou signale une anomalie.
 */
@Entity
@Table(name = "operator_statement_lines", indexes = {
    @Index(name = "idx_stmt_lines_operator_ref",  columnList = "operator_reference"),
    @Index(name = "idx_stmt_lines_statement_id",  columnList = "statement_id"),
    @Index(name = "idx_stmt_lines_discrepancy",   columnList = "statement_id,discrepancy_type")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OperatorStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "statement_id", nullable = false)
    private UUID statementId;

    /** Référence de la transaction côté opérateur. */
    @Column(name = "operator_reference", nullable = false)
    private String operatorReference;

    /** Référence Ebithex trouvée par réconciliation (null si MISSING_IN_EBITHEX). */
    private String ebithexReference;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    /** Statut tel que rapporté par l'opérateur (ex : "SUCCESS", "FAILED"). */
    @Column(name = "operator_status", nullable = false, length = 50)
    private String operatorStatus;

    private LocalDateTime operatorDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private DiscrepancyType discrepancyType;

    @Column(columnDefinition = "TEXT")
    private String discrepancyNote;

    @CreationTimestamp
    private LocalDateTime createdAt;
}