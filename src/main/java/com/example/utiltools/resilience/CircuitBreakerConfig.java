package com.example.utiltools.resilience;

import java.time.Duration;

public record CircuitBreakerConfig(int failureThreshold, int successThreshold,
                                   Duration cooldown, Duration probeLease) {

    /** Default lifetime of a HALF_OPEN probe claim: a probe that does not finish within it is superseded. */
    public static final Duration DEFAULT_PROBE_LEASE = Duration.ofSeconds(10);

    public CircuitBreakerConfig {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (successThreshold < 1) {
            throw new IllegalArgumentException("successThreshold must be >= 1");
        }
        if (cooldown == null || cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must be a non-negative duration");
        }
        if (probeLease == null || probeLease.isZero() || probeLease.isNegative()) {
            throw new IllegalArgumentException("probeLease must be a positive duration");
        }
    }

    /** Back-compatible 3-argument form: a {@link #DEFAULT_PROBE_LEASE 10s} probe lease. */
    public CircuitBreakerConfig(int failureThreshold, int successThreshold, Duration cooldown) {
        this(failureThreshold, successThreshold, cooldown, DEFAULT_PROBE_LEASE);
    }

    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(5, 2, Duration.ofSeconds(30), DEFAULT_PROBE_LEASE);
    }
}
