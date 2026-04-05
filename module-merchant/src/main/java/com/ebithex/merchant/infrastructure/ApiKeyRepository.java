package com.ebithex.merchant.infrastructure;

import com.ebithex.merchant.domain.ApiKey;
import com.ebithex.shared.apikey.ApiKeyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Recherche une clé active par son hash courant OU par son hash précédent
     * encore dans la période de grâce. Utilisé par ApiKeyAuthFilter.
     */
    @Query("""
        SELECT k FROM ApiKey k
        WHERE k.active = true
          AND (k.keyHash = :hash
               OR (k.previousHash = :hash AND k.previousExpiresAt > :now))
        """)
    Optional<ApiKey> findByHash(@Param("hash") String hash, @Param("now") LocalDateTime now);

    List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    List<ApiKey> findByMerchantIdAndTypeOrderByCreatedAtDesc(UUID merchantId, ApiKeyType type);


    /**
     * Clés actives créées avant {@code agingCutoff} dont le rappel de rotation
     * n'a pas encore été envoyé (ou envoyé avant {@code reminderCutoff}).
     */
    @Query("""
        SELECT k FROM ApiKey k
        WHERE k.active = true
          AND k.createdAt < :agingCutoff
          AND (k.agingReminderSentAt IS NULL OR k.agingReminderSentAt < :reminderCutoff)
        """)
    List<ApiKey> findAging(@Param("agingCutoff") LocalDateTime agingCutoff,
                           @Param("reminderCutoff") LocalDateTime reminderCutoff);

    /**
     * Clés actives ayant dépassé leur seuil de rotation forcée.
     * Utilise SQL natif PostgreSQL pour comparer createdAt avec l'intervalle dynamique.
     */
    @Query(value = """
        SELECT * FROM api_keys
        WHERE active = true
          AND rotation_required_days IS NOT NULL
          AND created_at < NOW() - (rotation_required_days || ' days')::INTERVAL
        """, nativeQuery = true)
    List<ApiKey> findOverdueForForcedRotation();

    /** Met à jour last_used_at sans charger l'entité complète. */
    @Modifying
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    void updateLastUsedAt(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** Désactive toutes les clés d'un marchand — révocation d'urgence. */
    @Modifying
    @Query("UPDATE ApiKey k SET k.active = false WHERE k.merchantId = :merchantId AND k.active = true")
    int deactivateAllForMerchant(@Param("merchantId") UUID merchantId);
}
