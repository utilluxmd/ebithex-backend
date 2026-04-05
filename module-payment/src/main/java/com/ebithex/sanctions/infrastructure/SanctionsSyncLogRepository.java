package com.ebithex.sanctions.infrastructure;

import com.ebithex.sanctions.domain.SanctionsSyncLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SanctionsSyncLogRepository extends JpaRepository<SanctionsSyncLog, UUID> {

    /** Dernier log de synchronisation pour une liste donnée. */
    Optional<SanctionsSyncLog> findTopByListNameOrderBySyncedAtDesc(String listName);

    /** Historique complet d'une liste (ordre décroissant). */
    List<SanctionsSyncLog> findByListNameOrderBySyncedAtDesc(String listName);

    /** Les N derniers logs toutes listes confondues. */
    @Query("SELECT l FROM SanctionsSyncLog l ORDER BY l.syncedAt DESC")
    List<SanctionsSyncLog> findRecent(Pageable pageable);
}
