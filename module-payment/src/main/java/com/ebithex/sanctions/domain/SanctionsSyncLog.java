package com.ebithex.sanctions.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Journal d'une opération de synchronisation d'une liste de sanctions.
 *
 * <p>Enregistré après chaque appel à {@link com.ebithex.sanctions.application.SanctionsListSyncService},
 * qu'il soit déclenché automatiquement par le job hebdomadaire ou manuellement via l'API back-office.
 */
@Entity
@Table(name = "sanctions_sync_log", indexes = {
    @Index(name = "idx_sync_log_list", columnList = "listName"),
    @Index(name = "idx_sync_log_at",   columnList = "syncedAt")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SanctionsSyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Identifiant de la liste synchronisée. */
    @Column(nullable = false, length = 30)
    private String listName;

    /** Horodatage de la synchronisation. */
    @Column(nullable = false)
    private LocalDateTime syncedAt;

    /** Résultat : SUCCESS | FAILED | PARTIAL. */
    @Column(nullable = false, length = 20)
    private String status;

    /** Nombre d'entrées importées (0 en cas d'échec). */
    @Builder.Default
    @Column(nullable = false)
    private int entriesImported = 0;

    /** Message d'erreur en cas d'échec (null si succès). */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Durée de la synchronisation en millisecondes. */
    @Column
    private Long durationMs;
}
