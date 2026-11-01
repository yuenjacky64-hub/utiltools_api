package com.example.utiltools.resilience;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit CLOSED / OPEN / HALF_OPEN state machine over an injected {@link Clock}.
 *
 * <p>All state mutation and audit writing happen under one monitor; the delegate call happens
 * outside it, so a slow delegate can never block fast-fails or the introspection endpoint.
 * Admission is decided before the delegate is invoked, so a delegate failure can never be
 * confused with a fast-fail.</p>
 */
public final class CircuitBreaker {

    private static final int MAX_REASON_LENGTH = 200;

    private final String name;
    private final CircuitBreakerConfig config;
    private final AuditTrail auditTrail;
    private final Clock clock;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private Instant openedAt;
    private Instant resetAt;
    private boolean probeInFlight;
    /** Marks the thread that already won admission, so a nested guarded call is not counted twice. */
    private final ThreadLocal<Boolean> admitted = new ThreadLocal<>();

    public CircuitBreaker(String name, CircuitBreakerConfig config, AuditTrail auditTrail, Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.config = Objects.requireNonNull(config, "config");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Guarded call: fast-fails with {@link CircuitBreakerOpenException} while the circuit is open. */
    public <T> T execute(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (Boolean.TRUE.equals(admitted.get())) {
            // Composed call (for example JsonService.query() inside a probe): the outer frame owns
            // admission and the success/failure bookkeeping, so this frame is a plain pass-through.
            return action.get();
        }
        admit();
        admitted.set(Boolean.TRUE);
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            onDelegateFailure(e);
            throw e;
        } catch (Error e) {
            onDelegateFailure(e);
            throw e;
        } finally {
            admitted.remove();
        }
        onDelegateSuccess();
        return result;
    }

    /**
     * Forced trial call. In the {@code OPEN} state the pending cooldown edge is taken early
     * (per the mandated shape in the implementation plan), so the trial is admitted in
     * {@code HALF_OPEN}. The delegate's own exception is never rethrown; only a refused
     * admission (for example a probe already in flight) propagates.
     */
    public ProbeResult probe(Supplier<?> action) {
        Objects.requireNonNull(action, "action");
        synchronized (this) {
            if (state == CircuitState.OPEN && clock.instant().isBefore(resetAt)) {
                resetAt = clock.instant();
            }
        }
        try {
            execute(action);
            return new ProbeResult(state(), true, null, resetAt());
        } catch (CircuitBreakerOpenException e) {
            throw e;
        } catch (RuntimeException e) {
            return new ProbeResult(state(), false, reasonOf(e, "probe failed"), resetAt());
        }
    }

    public synchronized CircuitState state() {
        return state;
    }

    public String name() {
        return name;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized int consecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public synchronized Instant resetAt() {
        return resetAt;
    }

    public AuditTrail auditTrail() {
        return auditTrail;
    }

    private void admit() {
        synchronized (this) {
            Instant now = clock.instant();
            if (state == CircuitState.OPEN) {
                if (now.isBefore(resetAt)) {
                    throw new CircuitBreakerOpenException(state, resetAt, "cooldown active until " + resetAt);
                }
                consecutiveSuccesses = 0;
                enterState(CircuitState.HALF_OPEN, CircuitTrigger.COOLDOWN_ELAPSED, "cooldown elapsed");
            }
            if (state == CircuitState.HALF_OPEN) {
                if (probeInFlight) {
                    throw new CircuitBreakerOpenException(state, resetAt, "probe already in flight");
                }
                probeInFlight = true;
            }
        }
    }

    private void onDelegateSuccess() {
        synchronized (this) {
            consecutiveFailures = 0;
            consecutiveSuccesses++;
            if (state == CircuitState.HALF_OPEN) {
                probeInFlight = false;
                if (consecutiveSuccesses >= config.successThreshold()) {
                    enterState(CircuitState.CLOSED, CircuitTrigger.PROBE_SUCCEEDED, "probe succeeded");
                    consecutiveFailures = 0;
                    consecutiveSuccesses = 0;
                }
            }
        }
    }

    private void onDelegateFailure(Throwable error) {
        synchronized (this) {
            consecutiveSuccesses = 0;
            if (state == CircuitState.HALF_OPEN) {
                probeInFlight = false;
                consecutiveFailures++;
                enterState(CircuitState.OPEN, CircuitTrigger.PROBE_FAILED, reasonOf(error, "probe failed"));
            } else if (state == CircuitState.CLOSED) {
                consecutiveFailures++;
                if (consecutiveFailures >= config.failureThreshold()) {
                    enterState(CircuitState.OPEN, CircuitTrigger.FAILURE_THRESHOLD_REACHED,
                            "failure threshold reached");
                }
            }
        }
    }

    private void enterState(CircuitState to, CircuitTrigger trigger, String reason) {
        CircuitState from = state;
        auditTrail.record(name, from, to, trigger, reason);
        state = to;
        if (to == CircuitState.OPEN) {
            openedAt = clock.instant();
            resetAt = openedAt.plus(config.cooldown());
        } else if (to == CircuitState.CLOSED) {
            openedAt = null;
            resetAt = null;
        }
    }

    private static String reasonOf(Throwable error, String fallback) {
        if (error == null) {
            return fallback;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.length() <= MAX_REASON_LENGTH ? message : message.substring(0, MAX_REASON_LENGTH);
    }

    public record ProbeResult(CircuitState state, boolean probeSucceeded, String error, Instant resetAt) {}
}
