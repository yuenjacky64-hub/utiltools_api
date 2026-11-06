package com.example.utiltools.ratelimit;

/**
 * Read-only snapshot of one client's sliding-window rate-limit state.
 *
 * @param limit     configured budget for the window
 * @param remaining admitted requests still available in the window ({@code >= 0})
 * @param resetAt   epoch second at which the window frees up again
 * @param allowed   whether another request would be admitted right now
 */
public record RateLimitStatusResponse(int limit, int remaining, long resetAt, boolean allowed) {
}
