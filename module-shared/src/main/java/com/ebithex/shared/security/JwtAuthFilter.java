package com.ebithex.shared.security;

import com.ebithex.shared.sandbox.SandboxContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT authentication is handled by ApiKeyAuthFilter.
 * This filter ensures the sandbox context is always cleared as a safety net.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(req, res);
        } finally {
            SandboxContextHolder.clear();
        }
    }
}
