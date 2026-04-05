package com.ebithex.sanctions.infrastructure;

import com.ebithex.sanctions.domain.SanctionsEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface SanctionsRepository extends JpaRepository<SanctionsEntry, UUID> {

    /** Toutes les entrées actives (chargement en mémoire pour le screening). */
    List<SanctionsEntry> findByIsActiveTrue();

    /** Entrées actives pour un pays spécifique. */
    List<SanctionsEntry> findByCountryCodeAndIsActiveTrue(String countryCode);

    /** Supprime toutes les entrées d'une liste (pour rechargement). */
    @Modifying
    @Transactional
    @Query("DELETE FROM SanctionsEntry e WHERE e.listName = :listName")
    void deleteByListName(@Param("listName") String listName);

    /** Nombre d'entrées actives par liste. */
    long countByListNameAndIsActiveTrue(String listName);
}