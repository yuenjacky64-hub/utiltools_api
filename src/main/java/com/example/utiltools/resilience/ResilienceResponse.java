package com.example.utiltools.resilience;

import com.example.utiltools.json.service.JsonQueryFallbackCache;

import java.time.Instant;
import java.util.List;

/**
 * The global block ({@code state}, counters, lease) is the {@link Fingerprints#DEFAULT} machine;
 * {@code fingerprints} adds the per-fingerprint breakdown alongside it.
 * {@code fallbackCache} is the last-known-good cache's bounded counter snapshot.
 */
public record ResilienceResponse(
        String name,
        CircuitState state,
        int consecutiveFailures,
        int consecutiveSuccesses,
        Instant resetAt,
        boolean probeInFlight,
        Instant probeLeaseExpiresAt,
        int auditCapacity,
        List<AuditEntry> audit,
        List<CircuitBreaker.FingerprintStateView> fingerprints,
        JsonQueryFallbackCache.Stats fallbackCache
) {}
