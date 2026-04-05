package com.ebithex.payment.infrastructure;

import com.ebithex.payment.domain.FeeRule;
import com.ebithex.shared.domain.OperatorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface FeeRuleRepository extends JpaRepository<FeeRule, UUID> {

    /**
     * Résout toutes les règles candidates pour (merchantId, operator, country, amount).
     *
     * La requête retourne les règles actives, dans leur fenêtre de validité,
     * applicables au montant, ordonnées par priorité décroissante.
     * La logique de sélection de la meilleure règle est dans FeeService.
     */
    @Query("""
        SELECT r FROM FeeRule r
        WHERE r.active = true
          AND r.validFrom <= :now
          AND (r.validUntil IS NULL OR r.validUntil >= :now)
          AND (r.minAmount IS NULL OR r.minAmount <= :amount)
          AND (r.maxAmount IS NULL OR r.maxAmount >= :amount)
          AND (
               r.merchantId = :merchantId
            OR r.operator   = :operator
            OR r.country    = :country
            OR (r.merchantId IS NULL AND r.operator IS NULL AND r.country IS NULL)
          )
        ORDER BY r.priority DESC, r.createdAt ASC
        """)
    List<FeeRule> findCandidates(
        @Param("merchantId") UUID merchantId,
        @Param("operator")   OperatorType operator,
        @Param("country")    String country,
        @Param("amount")     BigDecimal amount,
        @Param("now")        LocalDateTime now
    );

    List<FeeRule> findAllByOrderByPriorityDescCreatedAtAsc();

    List<FeeRule> findByMerchantIdOrderByPriorityDesc(UUID merchantId);
}