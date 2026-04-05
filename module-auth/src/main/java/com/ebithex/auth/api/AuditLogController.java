package com.ebithex.auth.api;

import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.audit.AuditLog;
import com.ebithex.shared.audit.AuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Consultation du journal d'audit back-office.
 *
 * Accès : ADMIN, SUPER_ADMIN uniquement (non accessible au SUPPORT)
 */
@RestController
@RequestMapping("/internal/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Back-office — Journal d'audit")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Lister les entrées d'audit (filtrable par action et entityType)")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
            auditLogRepository.search(action, entityType, pageable)));
    }

    @GetMapping("/staff-users/{staffUserId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Historique d'audit d'un utilisateur back-office")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> byStaffUser(
            @PathVariable UUID staffUserId,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
            auditLogRepository.findByOperatorIdOrderByCreatedAtDesc(staffUserId, pageable)));
    }

    @GetMapping("/{entityType}/{entityId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Historique d'audit d'une entité (ex: Merchant/{id})")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> byEntity(
            @PathVariable String entityType,
            @PathVariable String entityId,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
            auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType, entityId, pageable)));
    }
}