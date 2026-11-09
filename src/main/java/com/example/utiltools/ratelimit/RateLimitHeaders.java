package com.example.utiltools.ratelimit;

/**
 * Shared names of the rate-limit response headers.
 *
 * <p>The filter writes them on every admitted or rejected request and the status endpoint writes
 * the limit/remaining/reset names, so clients can parse one contract regardless of which component
 * answered. {@link #COST} is charged per request and therefore filter-only.</p>
 */
public final class RateLimitHeaders {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RESET = "X-RateLimit-Reset";
    public static final String COST = "X-RateLimit-Cost";

    private RateLimitHeaders() {
    }
}
