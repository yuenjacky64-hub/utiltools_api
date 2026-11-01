package com.example.utiltools.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Correlates every {@code /api/v1/**} call with an {@code X-Request-Id}.
 *
 * <p>An incoming header matching {@code ^[A-Za-z0-9_-]{8,64}$} is reused, anything
 * else (including malformed values) is replaced by a generated UUID. The resolved id
 * is stored as a request attribute and in {@link RequestContext}, echoed on the
 * response, and always cleared from the ThreadLocal in a {@code finally} block.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = RequestContext.normalize(request.getHeader(RequestContext.HEADER))
                .orElseGet(() -> UUID.randomUUID().toString());

        request.setAttribute(RequestContext.ATTRIBUTE, requestId);
        RequestContext.set(requestId);
        response.setHeader(RequestContext.HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }
}
