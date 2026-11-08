package com.example.utiltools.resilience;

import java.time.Instant;
import java.util.List;

/**
 * The global block ({@code state}, counters, lease) is the {@link Fingerprints#DEFAULT} machine;
 * {@code fingerprints} adds the per-fingerprint breakdown alongside it.
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
        List<CircuitBreaker.FingerprintStateView> fingerprints
) {}
