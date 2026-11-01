package com.example.utiltools.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration surface for the {@code /api/v1/utils/*} sliding-window rate limiter.
 *
 * <p>Defaults are deliberately safe for an unauthenticated public API: limiting is on,
 * the budget is conservative, and {@code X-Forwarded-For} is not trusted unless an
 * operator explicitly says a trusted proxy terminates traffic.</p>
 */
@ConfigurationProperties(prefix = "utiltools.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") int requestsPerWindow,
        @DefaultValue("60000") long windowMs,
        @DefaultValue("30000") long cleanupIntervalMs,
        @DefaultValue("10000") int maxTrackedClients,
        @DefaultValue("false") boolean trustForwardedHeader
) {

    public RateLimitProperties {
        if (requestsPerWindow < 1) {
            throw new IllegalArgumentException("utiltools.ratelimit.requests-per-window must be >= 1");
        }
        if (windowMs < 1) {
            throw new IllegalArgumentException("utiltools.ratelimit.window-ms must be >= 1");
        }
        if (cleanupIntervalMs < 1) {
            throw new IllegalArgumentException("utiltools.ratelimit.cleanup-interval-ms must be >= 1");
        }
        if (maxTrackedClients < 1) {
            throw new IllegalArgumentException("utiltools.ratelimit.max-tracked-clients must be >= 1");
        }
    }
}
