package com.ebithex.shared.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByOperatorIdOrderByCreatedAtDesc(UUID operatorId, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
        String entityType, String entityId, Pageable pageable);

    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:action IS NULL OR a.action = :action)
          AND (:entityType IS NULL OR a.entityType = :entityType)
        ORDER BY a.createdAt DESC
        """)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("entityType") String entityType,
                          Pageable pageable);
}