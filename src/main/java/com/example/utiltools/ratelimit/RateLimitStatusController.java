package com.example.utiltools.ratelimit;

import java.time.Clock;

import com.example.utiltools.common.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only window into the caller's own sliding-window rate-limit state.
 *
 * <p>The endpoint sits behind {@link RateLimitFilter}, which already consumed one admission for the
 * very request being served, so it only ever <em>reads</em> via
 * {@link SlidingWindowRateLimiter#peek(String)}. It never calls {@code tryAcquire}: that would
 * double-count and silently halve every client's budget. It reports state and never denies — HTTP
 * 200 is returned even when {@code allowed=false}; the following request is the one the filter
 * answers with 429.</p>
 */
@RestController
@RequestMapping("/api/v1/utils")
@Tag(name = "Rate Limit Status", description = "Read-only inspection of the caller's sliding-window rate limit state")
public class RateLimitStatusController {

    private static final Logger log = LoggerFactory.getLogger(RateLimitStatusController.class);

    private final SlidingWindowRateLimiter limiter;
    private final RateLimitProperties properties;
    private final Clock clock;

    public RateLimitStatusController(SlidingWindowRateLimiter limiter, RateLimitProperties properties,
                                     Clock clock) {
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/rate-limit/status")
    @Operation(summary = "Inspect the caller's sliding-window rate limit state",
            description = "Returns limit, remaining, resetAt and allowed for the calling client, mirrors them in the "
                    + "X-RateLimit-Limit/Remaining/Reset headers, and consumes no rate limit budget")
    public ApiResponse<RateLimitStatusResponse> status(HttpServletRequest request,
                                                      HttpServletResponse response) {
        SlidingWindowRateLimiter.Admission admission;
        try {
            admission = limiter.peek(ClientKeyResolver.resolve(request, properties));
        } catch (RuntimeException ex) {
            // Totality: a broken limiter must fail open, never surface as a 5xx.
            log.warn("rate limit status failed, failing open: {}", ex.toString());
            admission = fullBudget();
        }

        int limit = (int) admission.limit();
        int remaining = Math.max(0, (int) admission.remaining());
        long resetAt = admission.resetEpochSeconds();

        response.setHeader(RateLimitHeaders.LIMIT, Integer.toString(limit));
        response.setHeader(RateLimitHeaders.REMAINING, Integer.toString(remaining));
        response.setHeader(RateLimitHeaders.RESET, Long.toString(resetAt));

        return ApiResponse.success(
                new RateLimitStatusResponse(limit, remaining, resetAt, admission.allowed()),
                "Rate limit status retrieved");
    }

    private SlidingWindowRateLimiter.Admission fullBudget() {
        long limit = properties.requestsPerWindow();
        long resetAt = ceilDiv(clock.millis() + properties.windowMs(), 1000L);
        return new SlidingWindowRateLimiter.Admission(true, limit, limit, 0L, resetAt);
    }

    private static long ceilDiv(long dividend, long divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }
}
