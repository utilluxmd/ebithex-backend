package com.ebithex.payment.infrastructure;

import com.ebithex.shared.security.EbithexPrincipal;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Aspect de sécurité : activation automatique du filtre Hibernate {@code merchantFilter}
 * sur toutes les entités annotées {@code @Filter(name = "merchantFilter")}.
 *
 * <p><b>Comportement :</b>
 * <ul>
 *   <li>Si le principal courant est un {@link EbithexPrincipal} marchand (merchantId non null),
 *       le filtre est activé → toutes les lectures d'entités Transaction/Payout
 *       sont restreintes au merchantId du principal.</li>
 *   <li>Si le principal est un opérateur back-office ou nul (batch jobs, admin),
 *       le filtre n'est PAS activé → accès cross-marchands légitime.</li>
 * </ul>
 *
 * <p><b>Portée :</b> toutes les méthodes publiques des services dans
 * {@code com.ebithex.payment} et {@code com.ebithex.payout}.
 *
 * <p><b>Thread-safety :</b> {@link Session} est liée à la transaction courante
 * (même thread). Virtual threads : chaque virtual thread obtient son propre
 * contexte de sécurité via {@link SecurityContextHolder} en mode INHERITABLETHREADLOCAL
 * (configurer si nécessaire — cf. application.properties).
 */
@Aspect
@Component
@Slf4j
public class MerchantFilterAspect {

    private static final String FILTER_NAME = "merchantFilter";

    @PersistenceContext
    private EntityManager entityManager;

    @Before("execution(public * com.ebithex.payment.application.*Service.*(..))" +
            " || execution(public * com.ebithex.payout.application.*Service.*(..))")
    public void activateMerchantFilterIfApplicable() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof EbithexPrincipal principal)) {
            return; // batch job ou contexte non authentifié — pas de filtre
        }

        UUID merchantId = principal.merchantId();
        if (merchantId == null) {
            return; // opérateur back-office (staff user) — pas de filtre
        }

        try {
            Session session = entityManager.unwrap(Session.class);
            if (session.getEnabledFilter(FILTER_NAME) == null) {
                session.enableFilter(FILTER_NAME)
                       .setParameter("merchantId", merchantId.toString());
                log.trace("Filtre Hibernate 'merchantFilter' activé pour merchantId={}", merchantId);
            }
        } catch (Exception e) {
            // Fail-open : si l'activation échoue (ex. hors session active),
            // on log un warning mais on ne bloque pas la requête.
            // Le service devra s'assurer de vérifier le merchantId manuellement.
            log.warn("Impossible d'activer le filtre Hibernate merchantFilter : {}", e.getMessage());
        }
    }
}
