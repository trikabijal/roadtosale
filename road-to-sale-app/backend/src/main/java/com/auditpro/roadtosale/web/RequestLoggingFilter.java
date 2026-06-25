package com.auditpro.roadtosale.web;

import com.auditpro.roadtosale.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs each request: method, path, status, latency, userId, dealershipId.
 * Never logs headers, tokens, passwords, or bodies (PRD §7 #23).
 * Ordered after the JWT filter so the principal is available.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            String userId = "-";
            String dealershipId = "-";
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
                userId = String.valueOf(u.userId());
                dealershipId = String.valueOf(u.dealershipId());
            }
            log.info("{} {} -> {} ({}ms) userId={} dealershipId={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    latencyMs, userId, dealershipId);
        }
    }
}
