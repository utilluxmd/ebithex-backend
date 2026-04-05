package com.ebithex.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injecte un identifiant de corrélation dans le MDC Logback pour chaque requête.
 *
 * Priorité : s'exécute EN PREMIER dans la chaîne (Order = 1) pour que toutes
 * les lignes de log de la requête portent le correlationId, y compris les
 * lignes émises par les filtres d'auth.
 *
 * Clé MDC injectée : "correlationId"
 * Header d'entrée  : X-Request-ID (réutilisé si présent, généré sinon)
 * Header de sortie : X-Request-ID (retourné dans la réponse)
 *
 * IMPORTANT : MDC.clear() en finally — obligatoire pour éviter les fuites
 * de contexte dans le pool de threads.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String  CORRELATION_MDC_KEY    = "correlationId";
    public static final String  CORRELATION_HEADER     = "X-Request-ID";
    public static final String  MERCHANT_MDC_KEY       = "merchantId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}