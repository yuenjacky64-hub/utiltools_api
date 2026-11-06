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
 *
 * <p>A HALF_OPEN probe holds a <em>lease</em> ({@code probeLeaseExpiresAt}) against the monotonic
 * probe <em>generation</em> it was admitted with. Only the thread holding the current, unexpired
 * generation may move the breaker, so a probe that hangs past its lease and eventually returns can
 * no longer mutate state, counters or the audit trail. An expired claim is released exactly once
 * (trigger {@code PROBE_LEASE_EXPIRED}) by the next {@code admit()} or by {@link #reapExpiredProbe()}.</p>
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
    /** Monotonic HALF_OPEN claim counter; {@code 0} means "admitted while CLOSED, no probe claim". */
    private long probeGeneration;
    /** Deadline of the live HALF_OPEN claim; {@code null} means no claim is held. */
    private Instant probeLeaseExpiresAt;
    /** Marks the thread that already won admission, so a nested guarded call is not counted twice. */
    private final ThreadLocal<Long> admittedGeneration = new ThreadLocal<>();

    public CircuitBreaker(String name, CircuitBreakerConfig config, AuditTrail auditTrail, Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.config = Objects.requireNonNull(config, "config");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Guarded call: fast-fails with {@link CircuitBreakerOpenException} while the circuit is open. */
    public <T> T execute(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Long owned = admittedGeneration.get();
        if (owned != null) {
            // Composed call (for example JsonService.query() inside a probe): the outer frame owns
            // admission and the success/failure bookkeeping, so this frame is a plain pass-through.
            return action.get();
        }
        long generation = admit();
        admittedGeneration.set(generation);
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            onDelegateFailure(e, generation);
            throw e;
        } catch (Error e) {
            onDelegateFailure(e, generation);
            throw e;
        } finally {
            admittedGeneration.remove();
        }
        onDelegateSuccess(generation);
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

    /**
     * Releases an expired HALF_OPEN probe claim, recording exactly one {@code PROBE_LEASE_EXPIRED}
     * audit row per expired generation. Idempotent: a live claim, an already released claim, or a
     * breaker outside {@code HALF_OPEN} leaves the state untouched and returns {@code false}.
     */
    public synchronized boolean reapExpiredProbe() {
        if (state != CircuitState.HALF_OPEN) {
            return false;
        }
        Instant now = clock.instant();
        if (probeLeaseExpiresAt == null || now.isBefore(probeLeaseExpiresAt)) {
            return false;
        }
        releaseExpiredClaim();
        return true;
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

    /** {@code true} while a live, unexpired HALF_OPEN probe claim exists. */
    public synchronized boolean probeInFlight() {
        return isLeaseLive(clock.instant());
    }

    /** Deadline of the current HALF_OPEN probe claim, or {@code null} when no claim is held. */
    public synchronized Instant probeLeaseExpiresAt() {
        return probeLeaseExpiresAt;
    }

    public AuditTrail auditTrail() {
        return auditTrail;
    }

    /** @return the generation that owns this admission; {@code 0} for a CLOSED admission. */
    private long admit() {
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
                if (isLeaseLive(now)) {
                    throw new CircuitBreakerOpenException(state, resetAt, "probe already in flight");
                }
                if (probeLeaseExpiresAt != null) {
                    // The previous owner overran its lease: release it exactly once before claiming.
                    releaseExpiredClaim();
                }
                probeGeneration++;
                probeLeaseExpiresAt = now.plus(config.probeLease());
                return probeGeneration;
            }
            return 0L;
        }
    }

    private boolean isLeaseLive(Instant now) {
        return probeLeaseExpiresAt != null && now.isBefore(probeLeaseExpiresAt);
    }

    /** Caller must hold the monitor. Records one audit row and drops the deadline; stays HALF_OPEN. */
    private void releaseExpiredClaim() {
        enterState(CircuitState.HALF_OPEN, CircuitTrigger.PROBE_LEASE_EXPIRED, "probe lease expired");
        probeLeaseExpiresAt = null;
    }

    /** Only the current, unexpired HALF_OPEN generation may move the breaker. */
    private boolean ownsOutcome(long generation, Instant now) {
        if (generation == 0L) {
            return state != CircuitState.HALF_OPEN;
        }
        return state == CircuitState.HALF_OPEN
                && generation == probeGeneration
                && isLeaseLive(now);
    }

    private synchronized void onDelegateSuccess(long generation) {
        if (!ownsOutcome(generation, clock.instant())) {
            return;
        }
        consecutiveFailures = 0;
        consecutiveSuccesses++;
        if (state == CircuitState.HALF_OPEN) {
            probeLeaseExpiresAt = null;
            if (consecutiveSuccesses >= config.successThreshold()) {
                enterState(CircuitState.CLOSED, CircuitTrigger.PROBE_SUCCEEDED, "probe succeeded");
                consecutiveFailures = 0;
                consecutiveSuccesses = 0;
            }
        }
    }

    private synchronized void onDelegateFailure(Throwable error, long generation) {
        if (!ownsOutcome(generation, clock.instant())) {
            return;
        }
        consecutiveSuccesses = 0;
        if (state == CircuitState.HALF_OPEN) {
            probeLeaseExpiresAt = null;
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

    private void enterState(CircuitState to, CircuitTrigger trigger, String reason) {
        CircuitState from = state;
        auditTrail.record(name, from, to, trigger, reason);
        state = to;
        if (to != CircuitState.HALF_OPEN) {
            // Leaving HALF_OPEN always drops the claim, whether it closed, reopened or was fast-failed.
            probeLeaseExpiresAt = null;
        }
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
