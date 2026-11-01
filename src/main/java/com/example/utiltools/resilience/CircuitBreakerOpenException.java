package com.example.utiltools.resilience;

import java.time.Instant;

/**
 * Thrown when the breaker refuses a call because the circuit is open. Deliberately a direct
 * {@link RuntimeException} subclass (never an {@link IllegalArgumentException}) so that the
 * 503 handler wins over the existing 400 mapping.
 */
public class CircuitBreakerOpenException extends RuntimeException {

    private static final String MESSAGE = "Service unavailable: circuit breaker is open";

    private final CircuitState state;
    private final Instant resetAt;
    private final String reason;

    public CircuitBreakerOpenException(CircuitState state, Instant resetAt, String reason) {
        super(MESSAGE);
        this.state = state;
        this.resetAt = resetAt;
        this.reason = reason;
    }

    public CircuitState state() {
        return state;
    }

    public Instant resetAt() {
        return resetAt;
    }

    public String reason() {
        return reason;
    }
}
