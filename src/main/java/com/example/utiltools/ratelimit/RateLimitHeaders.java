package com.example.utiltools.ratelimit;

/**
 * Shared names of the rate-limit response headers.
 *
 * <p>The filter writes them on every admitted or rejected request and the status endpoint writes
 * the same three names, so clients can parse one contract regardless of which component answered.
 * </p>
 */
public final class RateLimitHeaders {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RESET = "X-RateLimit-Reset";

    private RateLimitHeaders() {
    }
}
