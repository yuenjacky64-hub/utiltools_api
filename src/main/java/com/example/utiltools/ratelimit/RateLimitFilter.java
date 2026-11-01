package com.example.utiltools.ratelimit;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.utiltools.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-cutting rate-limit gate for {@code /api/v1/utils/*}.
 *
 * <p>Registered only through a {@code FilterRegistrationBean} (never as a scanned {@code Filter}
 * bean) so MVC slices and the application keep exactly one registration, ordered before Spring
 * MVC dispatch. A rejected request never reaches the filter chain.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String UNKNOWN_CLIENT = "unknown";
    private static final String FORWARDED_HEADER = "X-Forwarded-For";
    private static final Pattern WELL_FORMED_ADDRESS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    private final SlidingWindowRateLimiter limiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(SlidingWindowRateLimiter limiter, RateLimitProperties properties,
                           ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        SlidingWindowRateLimiter.Admission admission;
        try {
            admission = limiter.tryAcquire(resolveClientKey(request));
        } catch (RuntimeException ex) {
            // Totality: a broken limiter must never take the nine utility endpoints down.
            log.warn("rate limiter failed, failing open: {}", ex.toString());
            chain.doFilter(request, response);
            return;
        }

        response.setHeader(HEADER_LIMIT, Long.toString(admission.limit()));
        response.setHeader(HEADER_REMAINING, Long.toString(Math.max(0L, admission.remaining())));
        response.setHeader(HEADER_RESET, Long.toString(admission.resetEpochSeconds()));

        if (admission.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        reject(response, admission);
    }

    private String resolveClientKey(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            remoteAddress = UNKNOWN_CLIENT;
        }
        if (!properties.trustForwardedHeader()) {
            return remoteAddress;
        }
        String forwarded = request.getHeader(FORWARDED_HEADER);
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddress;
        }
        String candidate = forwarded.split(",", 2)[0].trim();
        return WELL_FORMED_ADDRESS.matcher(candidate).matches() ? candidate : remoteAddress;
    }

    private void reject(HttpServletResponse response, SlidingWindowRateLimiter.Admission admission)
            throws IOException {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("retryAfterSeconds", admission.retryAfterSeconds());
        details.put("limit", admission.limit());
        details.put("windowMs", properties.windowMs());

        response.setStatus(STATUS_TOO_MANY_REQUESTS);
        response.setHeader(HEADER_RETRY_AFTER, Long.toString(Math.max(1L, admission.retryAfterSeconds())));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            String body = objectMapper.writeValueAsString(
                    ApiResponse.error("Rate limit exceeded", SlidingWindowRateLimiter.RATE_LIMITED, details));
            response.getWriter().write(body);
            response.getWriter().flush();
        } catch (IOException | RuntimeException ex) {
            log.warn("failed to write rate limit rejection body: {}", ex.toString());
            if (!response.isCommitted()) {
                response.sendError(STATUS_TOO_MANY_REQUESTS);
            }
        }
    }
}
