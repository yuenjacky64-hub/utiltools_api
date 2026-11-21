package com.example.utiltools.scheduling;

import com.example.utiltools.resilience.AuditEntry;
import com.example.utiltools.resilience.AuditTrail;
import com.example.utiltools.resilience.CircuitBreaker;
import com.example.utiltools.resilience.CircuitBreakerConfig;
import com.example.utiltools.resilience.CircuitBreakerOpenException;
import com.example.utiltools.resilience.CircuitState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-pass quarantine around the eight {@code @Scheduled} maintenance passes (REQ-01 / REQ-04).
 *
 * <p>One {@link CircuitBreaker} named {@value #BREAKER_NAME} carries eight independent
 * CLOSED / OPEN / HALF_OPEN machines, one per {@link MaintenancePass#name() fingerprint}. A pass
 * that throws on {@code utiltools.scheduling.pass-failure-threshold} consecutive ticks moves its own
 * machine to {@code OPEN} and records a {@code QUARANTINE} row in the bounded {@link AuditTrail};
 * while {@code OPEN} every scheduled tick is counted as {@code skipped} and the body is <em>not</em>
 * invoked; after {@code utiltools.scheduling.pass-cooldown-ms} exactly one {@code HALF_OPEN} probe
 * is admitted, and a successful probe closes the pass again.</p>
 *
 * <p>The breaker is built here and never exposed as a bean: the application context keeps exactly
 * one {@code CircuitBreaker} bean (INV-2). The guard owns telemetry only — state,
 * {@code consecutiveFailures} and {@code nextProbeAt} are read from the breaker, so the two can
 * never drift. Pass failures are never written into {@code AuditJournalService}: its reaper is one
 * of the guarded passes, so journaling a failure would recurse (INV-6).</p>
 *
 * <p>Every timestamp comes from the injected {@link Clock} (INV-7), which keeps cooldown and probe
 * behaviour testable without sleeping.</p>
 */
public final class MaintenancePassGuard {

    /** Breaker name shown in every audit row this guard produces. */
    public static final String BREAKER_NAME = "maintenance-passes";

    /** Bound on the guard's own audit trail; the only audit sink for pass failures (INV-6). */
    public static final int AUDIT_CAPACITY = 128;

    /** Upper bound on {@code recentTransitions} in the status route and in {@link #recentTransitions(int)}. */
    public static final int MAX_RECENT_TRANSITIONS = 20;

    private final CircuitBreaker breaker;
    private final Clock clock;
    /** Fixed shape after construction, so concurrent ticks never restructure it (INV-3). */
    private final Map<MaintenancePass, PassState> states = new EnumMap<>(MaintenancePass.class);

    public MaintenancePassGuard(MaintenanceSchedulingProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.breaker = new CircuitBreaker(
                BREAKER_NAME,
                new CircuitBreakerConfig(
                        properties.passFailureThreshold(),
                        properties.passSuccessThreshold(),
                        Duration.ofMillis(properties.passCooldownMs()),
                        Duration.ofMillis(properties.passProbeLeaseMs())),
                new AuditTrail(AUDIT_CAPACITY, clock),
                clock);
        for (MaintenancePass pass : MaintenancePass.values()) {
            states.put(pass, new PassState());
        }
    }

    /** A real guard with the documented defaults, so directly constructed owners stay guarded. */
    public static MaintenancePassGuard defaults() {
        return new MaintenancePassGuard(MaintenanceSchedulingProperties.defaults(), Clock.systemUTC());
    }

    /**
     * Runs {@code body} under the pass's own circuit.
     *
     * <p>A refused tick (the pass is {@code OPEN}, or its probe lease is taken) is counted as
     * {@code skipped} and the body is not invoked. A failing body is counted as {@code failed} and
     * rethrown unchanged, so the scheduler still logs the pass (INV-5).</p>
     */
    public void run(MaintenancePass pass, Runnable body) {
        Objects.requireNonNull(pass, "pass");
        Objects.requireNonNull(body, "body");
        PassState state = states.get(pass);
        try {
            breaker.execute(pass.name(), () -> {
                body.run();
                return null;
            });
            state.succeeded.incrementAndGet();
            state.lastSuccessAt = clock.instant();
            state.lastOutcome = Outcome.SUCCEEDED;
        } catch (CircuitBreakerOpenException refused) {
            state.skipped.incrementAndGet();
            state.lastOutcome = Outcome.SKIPPED;
        } catch (RuntimeException | Error failure) {
            state.failed.incrementAndGet();
            state.lastFailureAt = clock.instant();
            state.lastOutcome = Outcome.FAILED;
            throw failure;
        }
    }

    /** One row per {@link MaintenancePass}, in enum declaration order; read-only (INV-3). */
    public List<PassSnapshot> snapshot() {
        List<PassSnapshot> rows = new ArrayList<>(MaintenancePass.values().length);
        for (MaintenancePass pass : MaintenancePass.values()) {
            rows.add(snapshot(pass));
        }
        return List.copyOf(rows);
    }

    /** Current audit trail size; always {@code <= auditCapacity()} (INV-4). */
    public int auditSize() {
        return breaker.auditTrail().size();
    }

    /** Audit trail capacity, {@value #AUDIT_CAPACITY}. */
    public int auditCapacity() {
        return breaker.auditTrail().capacity();
    }

    /** Newest-first audit rows (matching {@link com.example.utiltools.resilience.AuditTrail#recent}), clamped to {@code [0, }{@value #MAX_RECENT_TRANSITIONS}{@code ]}. */
    public List<AuditEntry> recentTransitions(int limit) {
        int clamped = Math.max(0, Math.min(limit, MAX_RECENT_TRANSITIONS));
        if (clamped == 0) {
            return List.of();
        }
        return List.copyOf(breaker.auditTrail().recent(clamped));
    }

    private PassSnapshot snapshot(MaintenancePass pass) {
        PassState state = states.get(pass);
        CircuitState circuitState = breaker.state(pass.name());
        Instant nextProbeAt = circuitState == CircuitState.OPEN ? breaker.resetAt(pass.name()) : null;
        return new PassSnapshot(pass, circuitState, breaker.consecutiveFailures(pass.name()),
                state.lastOutcome, state.lastFailureAt, state.lastSuccessAt, nextProbeAt,
                state.succeeded.get(), state.failed.get(), state.skipped.get());
    }

    /** What the last admitted or refused tick did; {@code NONE} until the first tick. */
    public enum Outcome {
        NONE,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    /** Read-only view of one pass: circuit state plus the guard's telemetry counters. */
    public record PassSnapshot(MaintenancePass pass, CircuitState state, int consecutiveFailures,
            Outcome lastOutcome, Instant lastFailureAt, Instant lastSuccessAt, Instant nextProbeAt,
            long succeeded, long failed, long skipped) {
    }

    /**
     * Mutable telemetry for one pass. Counters are {@link AtomicLong}s and the timestamp/outcome
     * fields are {@code volatile}, so eight pool lanes can tick concurrently without a guard lock.
     */
    private static final class PassState {

        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong skipped = new AtomicLong();
        private volatile Instant lastFailureAt;
        private volatile Instant lastSuccessAt;
        private volatile Outcome lastOutcome = Outcome.NONE;
    }
}
