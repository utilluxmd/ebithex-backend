package com.ebithex.shared.audit;

import com.ebithex.shared.security.EbithexPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Service d'audit immuable.
 *
 * Chaque appel à {@code record()} insère une ligne dans audit_logs dans une
 * transaction REQUIRES_NEW : même si la transaction appelante est rollbackée,
 * la trace d'audit est conservée.
 *
 * L'identité de l'opérateur est extraite du SecurityContext courant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Enregistre une action administrative.
     *
     * @param action     code de l'action (KYC_APPROVED, FLOAT_ADJUSTED…)
     * @param entityType type de l'entité (Merchant, OperatorFloat…)
     * @param entityId   identifiant de l'entité
     * @param details    détails libres (JSON, motif de rejet…)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, String entityId, String details) {
        try {
            AuditLog.AuditLogBuilder builder = AuditLog.builder()
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(details)
                .ipAddress(resolveIpAddress());

            // Extraire l'opérateur du contexte de sécurité
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof EbithexPrincipal principal) {
                builder.operatorId(principal.id()).operatorEmail(principal.email());
            }

            auditLogRepository.save(builder.build());
        } catch (Exception e) {
            // L'audit ne doit jamais bloquer l'action métier
            log.error("Échec d'enregistrement de l'audit — action={} entity={}/{}: {}",
                action, entityType, entityId, e.getMessage());
        }
    }

    private String resolveIpAddress() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            String forwarded = attrs.getRequest().getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return attrs.getRequest().getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}