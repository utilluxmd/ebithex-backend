package com.ebithex.shared.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filtre HTTP qui mesure la durée de traitement de chaque requête.
 *
 * Si le temps dépasse le seuil ({@code ebithex.request.max-duration-ms}),
 * une réponse 503 est renvoyée. En pratique, pour une API REST synchrone,
 * cela intervient sur des requêtes dont le traitement dépasse le seuil
 * configuré (par défaut 30 s).
 *
 * Ce filtre est complémentaire à {@code spring.mvc.async.request-timeout}
 * (qui couvre les DeferredResult et les Callable).
 */
@Component
@Order(1)  // En premier pour mesurer l'ensemble du pipeline
@Slf4j
public class RequestTimeoutFilter implements Filter {

    @Value("${ebithex.request.max-duration-ms:30000}")
    private long maxDurationMs;

    @Override
    public void doFilter(ServletRequest servletRequest,
                         ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, resp);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > maxDurationMs) {
                log.warn("Requête lente détectée: {} {} → {}ms (seuil: {}ms)",
                    req.getMethod(), req.getRequestURI(), elapsed, maxDurationMs);
                // Si la réponse n'a pas encore été envoyée, retourner 503
                if (!resp.isCommitted()) {
                    resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    resp.setContentType("application/json");
                    resp.getWriter().write(
                        "{\"status\":503,\"errorCode\":\"REQUEST_TIMEOUT\","
                            + "\"message\":\"La requête a dépassé le délai maximum autorisé\"}");
                }
            }
        }
    }
}