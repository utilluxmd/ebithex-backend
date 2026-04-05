package com.ebithex.payment.domain;

import com.ebithex.shared.domain.OperatorType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Relevé journalier fourni par un opérateur Mobile Money.
 *
 * Chaque relevé couvre une journée pour un opérateur donné.
 * Les lignes détaillées sont dans {@link OperatorStatementLine}.
 */
@Entity
@Table(name = "operator_statements", indexes = {
    @Index(name = "idx_op_stmt_operator_date", columnList = "operator,statement_date"),
    @Index(name = "idx_op_stmt_status",        columnList = "status")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OperatorStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OperatorType operator;

    @Column(nullable = false)
    private LocalDate statementDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OperatorStatementStatus status = OperatorStatementStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int totalLines = 0;

    @Builder.Default
    private int matchedLines = 0;

    @Builder.Default
    private int discrepancyLines = 0;

    /** UUID de l'opérateur back-office qui a importé ce relevé. */
    private UUID importedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime importedAt;

    private LocalDateTime reconciledAt;

    @Column(columnDefinition = "TEXT")
    private String notes;
}