package com.ebithex.sanctions.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entrée d'une liste de sanctions réglementaires.
 * Chaque ligne représente une entité sanctionnée (individu, organisation, navire, aéronef).
 */
@Entity
@Table(name = "sanctions_entries", indexes = {
    @Index(name = "idx_sanctions_list",    columnList = "listName"),
    @Index(name = "idx_sanctions_country", columnList = "countryCode"),
    @Index(name = "idx_sanctions_name",    columnList = "entityName"),
    @Index(name = "idx_sanctions_active",  columnList = "isActive")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SanctionsEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Identifiant de la liste source (OFAC_SDN, UN_CONSOLIDATED, EU_CONSOLIDATED, ECOWAS_LOCAL, CUSTOM). */
    @Column(nullable = false, length = 30)
    private String listName;

    /** Nom officiel de l'entité sanctionnée. */
    @Column(nullable = false, length = 255)
    private String entityName;

    /** Noms alternatifs (aliases) séparés par des virgules. */
    @Column(columnDefinition = "TEXT")
    private String aliases;

    /** Code pays ISO 3166-1 alpha-2 associé à l'entité (nullable). */
    @Column(length = 5)
    private String countryCode;

    /** Type d'entité : INDIVIDUAL, ENTITY, VESSEL, AIRCRAFT. */
    @Column(length = 30)
    private String entityType;

    /** Indique si l'entrée est active (false = retirée sans suppression physique). */
    @Builder.Default
    private boolean isActive = true;

    @CreationTimestamp
    private LocalDateTime createdAt;
}