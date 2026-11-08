package com.example.utiltools.resilience;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit CLOSED / OPEN / HALF_OPEN state machine over an injected {@link Clock}, kept once per
 * <em>fingerprint</em>.
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
 *
 * <p>Each fingerprint owns an independent machine, counters, probe generation and lease, so a
 * failure for one caller moves only that caller's machine. The fingerprint supplied to
 * {@link #execute(String, Supplier)} is normalised by {@link Fingerprints#normalize(String)}; the
 * legacy no-argument entry points resolve {@link Fingerprints#DEFAULT}, which is the single global
 * machine the breaker used before fingerprints existed. When a non-default fingerprint moves to
 * {@code OPEN} it is <em>isolated</em>: a second audit row with trigger {@code QUARANTINE} names the
 * fingerprint that was quarantined.</p>
 *
 * <p>The machine map is bounded ({@code maxFingerprints}); a new key evicts the least-recently-used
 * {@code CLOSED} machine, and only when every machine is non-{@code CLOSED} does it evict the
 * least-recently-used machine overall. Read-only accessors never create a machine.</p>
 */
public final class CircuitBreaker {

    private static final int MAX_REASON_LENGTH = 200;

    /** Default cap on tracked fingerprints. */
    public static final int DEFAULT_MAX_FINGERPRINTS = 256;

    private final String name;
    private final CircuitBreakerConfig config;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final int maxFingerprints;
    /** Per-fingerprint state owner. Access order, so the head is the least recently used machine. */
    private final Map<String, Machine> machines = new LinkedHashMap<>(16, 0.75f, true);
    /** Marks the thread that already won admission, so a nested guarded call is not counted twice. */
    private final ThreadLocal<Admission> admittedGeneration = new ThreadLocal<>();

    public CircuitBreaker(String name, CircuitBreakerConfig config, AuditTrail auditTrail, Clock clock) {
        this(name, config, auditTrail, clock, DEFAULT_MAX_FINGERPRINTS);
    }

    public CircuitBreaker(String name, CircuitBreakerConfig config, AuditTrail auditTrail, Clock clock,
                          int maxFingerprints) {
        if (maxFingerprints < 1) {
            throw new IllegalArgumentException("maxFingerprints must be >= 1");
        }
        this.name = Objects.requireNonNull(name, "name");
        this.config = Objects.requireNonNull(config, "config");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxFingerprints = maxFingerprints;
    }

    /** Guarded call for the {@link Fingerprints#DEFAULT default} machine. */
    public <T> T execute(Supplier<T> action) {
        return execute(Fingerprints.DEFAULT, action);
    }

    /**
     * Guarded call scoped to {@code fingerprint}: fast-fails with {@link CircuitBreakerOpenException}
     * while <em>that</em> fingerprint's circuit is open. Other fingerprints are unaffected.
     */
    public <T> T execute(String fingerprint, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (admittedGeneration.get() != null) {
            // Composed call (for example JsonService.query() inside a probe): the outer frame owns
            // admission and the success/failure bookkeeping, so this frame is a plain pass-through.
            return action.get();
        }
        Admission admission = admit(fingerprint);
        admittedGeneration.set(admission);
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            onDelegateFailure(e, admission);
            throw e;
        } catch (Error e) {
            onDelegateFailure(e, admission);
            throw e;
        } finally {
            admittedGeneration.remove();
        }
        onDelegateSuccess(admission);
        return result;
    }

    /**
     * Forced trial call for the {@link Fingerprints#DEFAULT default} machine. In the {@code OPEN}
     * state the pending cooldown edge is taken early (per the mandated shape in the implementation
     * plan), so the trial is admitted in {@code HALF_OPEN}. The delegate's own exception is never
     * rethrown; only a refused admission (for example a probe already in flight) propagates.
     */
    public ProbeResult probe(Supplier<?> action) {
        return probe(Fingerprints.DEFAULT, action);
    }

    /** Forced trial call scoped to one fingerprint, with the same semantics as {@link #probe(Supplier)}. */
    public ProbeResult probe(String fingerprint, Supplier<?> action) {
        Objects.requireNonNull(action, "action");
        String key = Fingerprints.normalize(fingerprint);
        synchronized (this) {
            Machine machine = machineFor(key);
            if (machine.state == CircuitState.OPEN && clock.instant().isBefore(machine.resetAt)) {
                machine.resetAt = clock.instant();
            }
        }
        try {
            execute(key, action);
            return new ProbeResult(state(key), true, null, resetAt(key), key);
        } catch (CircuitBreakerOpenException e) {
            throw e;
        } catch (RuntimeException e) {
            return new ProbeResult(state(key), false, reasonOf(e, "probe failed"), resetAt(key), key);
        }
    }

    /**
     * Releases every expired HALF_OPEN probe claim, recording exactly one {@code PROBE_LEASE_EXPIRED}
     * audit row per expired generation. Idempotent per fingerprint: a live claim, an already released
     * claim, or a machine outside {@code HALF_OPEN} leaves that machine untouched.
     *
     * @return {@code true} if at least one claim was released
     */
    public synchronized boolean reapExpiredProbe() {
        Instant now = clock.instant();
        boolean released = false;
        for (Machine machine : machines.values()) {
            if (isLeaseExpired(machine, now)) {
                releaseExpiredClaim(machine);
                released = true;
            }
        }
        return released;
    }

    /** Single-fingerprint form of {@link #reapExpiredProbe()}; never creates a machine. */
    public synchronized boolean reapExpiredProbe(String fingerprint) {
        Machine machine = peek(fingerprint);
        if (machine == null) {
            return false;
        }
        Instant now = clock.instant();
        if (!isLeaseExpired(machine, now)) {
            return false;
        }
        releaseExpiredClaim(machine);
        return true;
    }

    /** Read-only lookup of the {@link Fingerprints#DEFAULT default} machine. */
    public synchronized CircuitState state() {
        return stateFor(Fingerprints.DEFAULT);
    }

    /** Read-only lookup; an unknown fingerprint reads as {@code CLOSED} and is never registered. */
    public synchronized CircuitState state(String fingerprint) {
        return stateFor(fingerprint);
    }

    public String name() {
        return name;
    }

    /** Cap on tracked fingerprints. */
    public int maxFingerprints() {
        return maxFingerprints;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailuresFor(Fingerprints.DEFAULT);
    }

    public synchronized int consecutiveFailures(String fingerprint) {
        return consecutiveFailuresFor(fingerprint);
    }

    public synchronized int consecutiveSuccesses() {
        return consecutiveSuccessesFor(Fingerprints.DEFAULT);
    }

    public synchronized int consecutiveSuccesses(String fingerprint) {
        return consecutiveSuccessesFor(fingerprint);
    }

    public synchronized Instant resetAt() {
        return resetAtFor(Fingerprints.DEFAULT);
    }

    public synchronized Instant resetAt(String fingerprint) {
        return resetAtFor(fingerprint);
    }

    /** {@code true} while a live, unexpired HALF_OPEN probe claim exists on the default machine. */
    public synchronized boolean probeInFlight() {
        return probeInFlightFor(Fingerprints.DEFAULT);
    }

    public synchronized boolean probeInFlight(String fingerprint) {
        return probeInFlightFor(fingerprint);
    }

    /** Deadline of the current HALF_OPEN probe claim on the default machine, or {@code null}. */
    public synchronized Instant probeLeaseExpiresAt() {
        return probeLeaseExpiresAtFor(Fingerprints.DEFAULT);
    }

    public synchronized Instant probeLeaseExpiresAt(String fingerprint) {
        return probeLeaseExpiresAtFor(fingerprint);
    }

    /**
     * Immutable per-fingerprint snapshot, sorted by fingerprint ascending so the JSON order is
     * deterministic. An unknown or absent fingerprint is not listed.
     */
    public synchronized List<FingerprintStateView> fingerprintStates() {
        Instant now = clock.instant();
        List<FingerprintStateView> views = new ArrayList<>(machines.size());
        for (Machine machine : machines.values()) {
            views.add(new FingerprintStateView(
                    machine.fingerprint,
                    machine.state,
                    machine.consecutiveFailures,
                    machine.consecutiveSuccesses,
                    machine.resetAt,
                    isLeaseLive(machine, now),
                    machine.probeLeaseExpiresAt,
                    machine.state != CircuitState.CLOSED));
        }
        views.sort(Comparator.comparing(FingerprintStateView::fingerprint));
        return List.copyOf(views);
    }

    public AuditTrail auditTrail() {
        return auditTrail;
    }

    /**
     * Admission decision for one fingerprint.
     *
     * @return the admission the caller must book its outcome with; {@code generation == 0} for a
     *         CLOSED admission, otherwise the claimed HALF_OPEN probe generation
     */
    public Admission admit(String fingerprint) {
        String key = Fingerprints.normalize(fingerprint);
        synchronized (this) {
            Machine machine = machineFor(key);
            Instant now = clock.instant();
            if (machine.state == CircuitState.OPEN) {
                if (now.isBefore(machine.resetAt)) {
                    throw new CircuitBreakerOpenException(machine.state, machine.resetAt,
                            "cooldown active until " + machine.resetAt);
                }
                machine.consecutiveSuccesses = 0;
                enterState(machine, CircuitState.HALF_OPEN, CircuitTrigger.COOLDOWN_ELAPSED, "cooldown elapsed");
            }
            if (machine.state == CircuitState.HALF_OPEN) {
                if (isLeaseLive(machine, now)) {
                    throw new CircuitBreakerOpenException(machine.state, machine.resetAt,
                            "probe already in flight");
                }
                if (machine.probeLeaseExpiresAt != null) {
                    // The previous owner overran its lease: release it exactly once before claiming.
                    releaseExpiredClaim(machine);
                }
                machine.probeGeneration++;
                machine.probeLeaseExpiresAt = now.plus(config.probeLease());
                return new Admission(machine.fingerprint, machine.probeGeneration);
            }
            return new Admission(machine.fingerprint, 0L);
        }
    }

    // ---- machine plumbing -----------------------------------------------------------------

    private CircuitState stateFor(String fingerprint) {
        Machine machine = peek(fingerprint);
        return machine == null ? CircuitState.CLOSED : machine.state;
    }

    private int consecutiveFailuresFor(String fingerprint) {
        Machine machine = peek(fingerprint);
        return machine == null ? 0 : machine.consecutiveFailures;
    }

    private int consecutiveSuccessesFor(String fingerprint) {
        Machine machine = peek(fingerprint);
        return machine == null ? 0 : machine.consecutiveSuccesses;
    }

    private Instant resetAtFor(String fingerprint) {
        Machine machine = peek(fingerprint);
        return machine == null ? null : machine.resetAt;
    }

    private boolean probeInFlightFor(String fingerprint) {
        Machine machine = peek(fingerprint);
        return machine != null && isLeaseLive(machine, clock.instant());
    }

    private Instant probeLeaseExpiresAtFor(String fingerprint) {
        Machine machine = peek(fingerprint);
        return machine == null ? null : machine.probeLeaseExpiresAt;
    }

    /** Read-only lookup: never registers a machine. Caller must hold the monitor (access order). */
    private Machine peek(String fingerprint) {
        return machines.get(Fingerprints.normalize(fingerprint));
    }

    /** Lookup-or-create. Caller must hold the monitor (access order). */
    private Machine machineFor(String key) {
        Machine existing = machines.get(key);
        if (existing != null) {
            return existing;
        }
        evictIfFull();
        Machine created = new Machine(key);
        machines.put(key, created);
        return created;
    }

    /**
     * Caller must hold the monitor. Keeps {@code machines.size() < maxFingerprints} so the following
     * insert cannot exceed the bound: the least-recently-used CLOSED machine goes first, and only when
     * every machine is non-CLOSED does the least-recently-used one overall go.
     */
    private void evictIfFull() {
        if (machines.size() < maxFingerprints) {
            return;
        }
        String victim = null;
        for (Map.Entry<String, Machine> entry : machines.entrySet()) {
            if (entry.getValue().state == CircuitState.CLOSED) {
                victim = entry.getKey();
                break;
            }
        }
        if (victim == null) {
            Iterator<String> leastRecentlyUsed = machines.keySet().iterator();
            if (leastRecentlyUsed.hasNext()) {
                victim = leastRecentlyUsed.next();
            }
        }
        if (victim != null) {
            machines.remove(victim);
        }
    }

    private boolean isLeaseLive(Machine machine, Instant now) {
        return machine.probeLeaseExpiresAt != null && now.isBefore(machine.probeLeaseExpiresAt);
    }

    private boolean isLeaseExpired(Machine machine, Instant now) {
        return machine.state == CircuitState.HALF_OPEN
                && machine.probeLeaseExpiresAt != null
                && !now.isBefore(machine.probeLeaseExpiresAt);
    }

    /** Caller must hold the monitor. Records one audit row and drops the deadline; stays HALF_OPEN. */
    private void releaseExpiredClaim(Machine machine) {
        enterState(machine, CircuitState.HALF_OPEN, CircuitTrigger.PROBE_LEASE_EXPIRED, "probe lease expired");
        machine.probeLeaseExpiresAt = null;
    }

    /** Only the current, unexpired HALF_OPEN generation of that one machine may move it. */
    private boolean ownsOutcome(Machine machine, long generation, Instant now) {
        if (generation == 0L) {
            return machine.state != CircuitState.HALF_OPEN;
        }
        return machine.state == CircuitState.HALF_OPEN
                && generation == machine.probeGeneration
                && isLeaseLive(machine, now);
    }

    private synchronized void onDelegateSuccess(Admission admission) {
        Machine machine = machines.get(admission.fingerprint());
        if (machine == null || !ownsOutcome(machine, admission.generation(), clock.instant())) {
            return;
        }
        machine.consecutiveFailures = 0;
        machine.consecutiveSuccesses++;
        if (machine.state == CircuitState.HALF_OPEN) {
            machine.probeLeaseExpiresAt = null;
            if (machine.consecutiveSuccesses >= config.successThreshold()) {
                enterState(machine, CircuitState.CLOSED, CircuitTrigger.PROBE_SUCCEEDED, "probe succeeded");
                machine.consecutiveFailures = 0;
                machine.consecutiveSuccesses = 0;
            }
        }
    }

    private synchronized void onDelegateFailure(Throwable error, Admission admission) {
        Machine machine = machines.get(admission.fingerprint());
        if (machine == null || !ownsOutcome(machine, admission.generation(), clock.instant())) {
            return;
        }
        machine.consecutiveSuccesses = 0;
        if (machine.state == CircuitState.HALF_OPEN) {
            machine.probeLeaseExpiresAt = null;
            machine.consecutiveFailures++;
            enterState(machine, CircuitState.OPEN, CircuitTrigger.PROBE_FAILED, reasonOf(error, "probe failed"));
        } else if (machine.state == CircuitState.CLOSED) {
            machine.consecutiveFailures++;
            if (machine.consecutiveFailures >= config.failureThreshold()) {
                enterState(machine, CircuitState.OPEN, CircuitTrigger.FAILURE_THRESHOLD_REACHED,
                        "failure threshold reached");
            }
        }
    }

    /** Caller must hold the monitor: one transition row, plus a QUARANTINE row when a non-default key isolates. */
    private void enterState(Machine machine, CircuitState to, CircuitTrigger trigger, String reason) {
        CircuitState from = machine.state;
        auditTrail.record(name, machine.fingerprint, from, to, trigger, reason);
        machine.state = to;
        if (to != CircuitState.HALF_OPEN) {
            // Leaving HALF_OPEN always drops the claim, whether it closed, reopened or was fast-failed.
            machine.probeLeaseExpiresAt = null;
        }
        if (to == CircuitState.OPEN) {
            machine.openedAt = clock.instant();
            machine.resetAt = machine.openedAt.plus(config.cooldown());
            if (!Fingerprints.DEFAULT.equals(machine.fingerprint)) {
                // Isolation of a split-off fingerprint: its own row, naming the quarantined key.
                auditTrail.record(name, machine.fingerprint, from, CircuitState.OPEN,
                        CircuitTrigger.QUARANTINE, "fingerprint quarantined: " + reason);
            }
        } else if (to == CircuitState.CLOSED) {
            machine.openedAt = null;
            machine.resetAt = null;
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

    /** The admission a caller books its outcome with: which machine, and which probe generation. */
    public record Admission(String fingerprint, long generation) {}

    /** Read-only view of one fingerprint's machine, as exposed by the resilience endpoint. */
    public record FingerprintStateView(String fingerprint, CircuitState state, int consecutiveFailures,
                                       int consecutiveSuccesses, Instant resetAt, boolean probeInFlight,
                                       Instant probeLeaseExpiresAt, boolean quarantined) {}

    public record ProbeResult(CircuitState state, boolean probeSucceeded, String error, Instant resetAt,
                              String fingerprint) {}

    /** The six former top-level state fields, verbatim, now one bucket per fingerprint. */
    private static final class Machine {

        private final String fingerprint;
        private CircuitState state = CircuitState.CLOSED;
        private int consecutiveFailures;
        private int consecutiveSuccesses;
        private Instant openedAt;
        private Instant resetAt;
        /** Monotonic HALF_OPEN claim counter; {@code 0} means "admitted while CLOSED, no probe claim". */
        private long probeGeneration;
        /** Deadline of the live HALF_OPEN claim; {@code null} means no claim is held. */
        private Instant probeLeaseExpiresAt;

        private Machine(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }
}
