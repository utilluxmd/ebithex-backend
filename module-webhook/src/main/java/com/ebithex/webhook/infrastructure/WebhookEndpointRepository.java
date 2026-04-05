package com.ebithex.webhook.infrastructure;

import com.ebithex.webhook.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    List<WebhookEndpoint> findByMerchantId(UUID merchantId);

    /**
     * Charge les endpoints actifs avec leurs events en un seul JOIN FETCH.
     * Évite le N+1 que génèrerait un findByMerchantIdAndActiveTrue avec FetchType.LAZY.
     */
    @Query("SELECT DISTINCT e FROM WebhookEndpoint e LEFT JOIN FETCH e.events " +
           "WHERE e.merchantId = :merchantId AND e.active = true")
    List<WebhookEndpoint> findActiveByMerchantIdWithEvents(@Param("merchantId") UUID merchantId);
}
