package com.ebithex.shared.audit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Journal d'audit immuable des actions back-office.
 *
 * Chaque action administrative (KYC, float, DLQ, login/logout) génère
 * une entrée immuable tracée avec l'identité de l'opérateur.
 *
 * JAMAIS modifié après insertion (pas de @Setter).
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = {
        @Index(name = "idx_audit_operator",   columnList = "operator_id"),
        @Index(name = "idx_audit_entity",     columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_action",     columnList = "action"),
        @Index(name = "idx_audit_created_at", columnList = "created_at DESC")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** UUID de l'opérateur ayant effectué l'action (null = action système) */
    @Column(name = "operator_id")
    private UUID operatorId;

    /** Email de l'opérateur au moment de l'action (dénormalisé pour auditabilité) */
    @Column(name = "operator_email", length = 255)
    private String operatorEmail;

    /** Action effectuée — ex: KYC_APPROVED, FLOAT_ADJUSTED, WEBHOOK_DLQ_RETRY */
    @Column(nullable = false, length = 60)
    private String action;

    /** Type d'entité concernée — ex: Merchant, OperatorFloat, WebhookDelivery */
    @Column(name = "entity_type", length = 50)
    private String entityType;

    /** ID de l'entité concernée (UUID sous forme de chaîne) */
    @Column(name = "entity_id", length = 100)
    private String entityId;

    /** Détails libres en JSON (motif de rejet, montant, etc.) */
    @Column(columnDefinition = "TEXT")
    private String details;

    /** IP du client HTTP ayant initié l'action */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}