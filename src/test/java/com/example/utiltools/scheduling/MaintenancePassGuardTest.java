package com.example.utiltools.scheduling;

import com.example.utiltools.resilience.CircuitState;
import com.example.utiltools.resilience.CircuitTrigger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOD-01..DOD-04, DOD-09, DOD-10: the per-pass quarantine around the eight scheduled passes.
 *
 * <p>Every test drives the guard through a {@link MutableClock}, so the failed transition, the
 * cooldown and the {@code HALF_OPEN} probe are asserted without sleeping. The guard is built from
 * the documented defaults (3 failures, 1 success, 60s cooldown, 10s probe lease) through a real
 * {@link MaintenanceSchedulingProperties} instance, so the property wiring is covered too.</p>
 */
@DisplayName("Maintenance pass quarantine (MaintenancePassGuardTest)")
final class MaintenancePassGuardTest {

    private static final MaintenancePass PASS = MaintenancePass.AUDIT_JOURNAL_REAP;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long COOLDOWN_MS = 60_000L;

    private MutableClock clock;
    private MaintenancePassGuard guard;
    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        guard = new MaintenancePassGuard(MaintenanceSchedulingProperties.defaults(), clock);
        workers = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    @DisplayName("three consecutive failures move the pass to OPEN; the fourth tick is skipped")
    void shouldQuarantineAfterThreeConsecutiveFailures() {
        AtomicInteger bodyInvocations = new AtomicInteger();

        for (int tick = 0; tick < 3; tick++) {
            assertThatThrownBy(() -> guard.run(PASS, () -> {
                bodyInvocations.incrementAndGet();
                throw new IllegalStateException("reaper blew up");
            })).isInstanceOf(IllegalStateException.class);
        }

        MaintenancePassGuard.PassSnapshot open = row(PASS);
        assertThat(bodyInvocations.get()).isEqualTo(3);
        assertThat(open.state()).isEqualTo(CircuitState.OPEN);
        assertThat(open.consecutiveFailures()).isEqualTo(3);
        assertThat(open.failed()).isEqualTo(3);
        assertThat(open.lastOutcome()).isEqualTo(MaintenancePassGuard.Outcome.FAILED);
        assertThat(open.lastFailureAt()).isEqualTo(START);
        assertThat(open.nextProbeAt()).isEqualTo(START.plusMillis(COOLDOWN_MS));

        // DOD-01: while OPEN the tick is counted as skipped and the body is not invoked.
        guard.run(PASS, bodyInvocations::incrementAndGet);

        MaintenancePassGuard.PassSnapshot skipped = row(PASS);
        assertThat(bodyInvocations.get()).isEqualTo(3);
        assertThat(skipped.skipped()).isEqualTo(1);
        assertThat(skipped.lastOutcome()).isEqualTo(MaintenancePassGuard.Outcome.SKIPPED);
        assertThat(skipped.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("cooldown elapsed admits one HALF_OPEN probe; a failing probe reopens with a fresh cooldown")
    void shouldReopenWithFreshCooldownWhenProbeFails() {
        quarantine(PASS);

        clock.advanceMillis(COOLDOWN_MS);
        assertThatThrownBy(() -> guard.run(PASS, () -> {
            throw new IllegalStateException("probe still failing");
        })).isInstanceOf(IllegalStateException.class);

        MaintenancePassGuard.PassSnapshot reopened = row(PASS);
        assertThat(reopened.state()).isEqualTo(CircuitState.OPEN);
        assertThat(reopened.failed()).isEqualTo(4);
        assertThat(reopened.nextProbeAt()).isEqualTo(START.plusMillis(2 * COOLDOWN_MS));

        // No clock advance: the pass is OPEN again, so the next tick is skipped, not probed.
        AtomicInteger probeInvocations = new AtomicInteger();
        guard.run(PASS, probeInvocations::incrementAndGet);
        assertThat(probeInvocations.get()).isZero();
        assertThat(row(PASS).skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("a succeeding probe closes the pass, resets consecutiveFailures and counts the success")
    void shouldCloseAndResetCountersWhenProbeSucceeds() {
        quarantine(PASS);

        clock.advanceMillis(COOLDOWN_MS);
        AtomicInteger probeInvocations = new AtomicInteger();
        guard.run(PASS, probeInvocations::incrementAndGet);

        MaintenancePassGuard.PassSnapshot closed = row(PASS);
        assertThat(probeInvocations.get()).isEqualTo(1);
        assertThat(closed.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(closed.consecutiveFailures()).isZero();
        assertThat(closed.succeeded()).isEqualTo(1);
        assertThat(closed.failed()).isEqualTo(3);
        assertThat(closed.lastOutcome()).isEqualTo(MaintenancePassGuard.Outcome.SUCCEEDED);
        assertThat(closed.lastSuccessAt()).isEqualTo(START.plusMillis(COOLDOWN_MS));
        assertThat(closed.nextProbeAt()).isNull();
    }

    @Test
    @DisplayName("CLOSED -> OPEN records exactly one QUARANTINE row and the audit trail stays bounded")
    void shouldRecordQuarantineInBoundedAuditTrail() {
        quarantine(PASS);

        assertThat(guard.recentTransitions(MaintenancePassGuard.MAX_RECENT_TRANSITIONS)).filteredOn(
                entry -> entry.trigger() == CircuitTrigger.QUARANTINE
                        && PASS.name().equals(entry.fingerprint()))
                .hasSize(1);

        // More than AUDIT_CAPACITY transitions: the trail must stay bounded (INV-4).
        for (int cycle = 0; cycle <= MaintenancePassGuard.AUDIT_CAPACITY; cycle++) {
            clock.advanceMillis(COOLDOWN_MS);
            assertThatThrownBy(() -> guard.run(PASS, () -> {
                throw new IllegalStateException("never recovers");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(guard.auditCapacity()).isEqualTo(MaintenancePassGuard.AUDIT_CAPACITY);
        assertThat(guard.auditSize()).isLessThanOrEqualTo(guard.auditCapacity());
        assertThat(guard.recentTransitions(Integer.MAX_VALUE))
                .hasSize(MaintenancePassGuard.MAX_RECENT_TRANSITIONS);
    }

    @Test
    @DisplayName("each pass keeps its own fingerprint, so quarantining one leaves the other seven CLOSED")
    void shouldKeepOneFingerprintPerPass() {
        quarantine(PASS);

        assertThat(guard.snapshot()).hasSize(MaintenancePass.values().length);
        assertThat(guard.snapshot()).extracting(MaintenancePassGuard.PassSnapshot::pass)
                .containsExactly(MaintenancePass.values());
        assertThat(guard.snapshot())
                .filteredOn(row -> row.state() == CircuitState.OPEN)
                .extracting(MaintenancePassGuard.PassSnapshot::pass)
                .containsExactly(PASS);
        assertThat(guard.snapshot())
                .filteredOn(row -> row.pass() != PASS)
                .allSatisfy(row -> assertThat(row.state()).isEqualTo(CircuitState.CLOSED));
    }

    @Test
    @DisplayName("a refused tick is skipped without invoking the body, a failure is rethrown, counters stay exact")
    void shouldCountSkipAndRethrowBodyFailure() throws Exception {
        // refused tick: no body invocation, one skip
        quarantine(PASS);
        AtomicInteger refusedInvocations = new AtomicInteger();
        guard.run(PASS, refusedInvocations::incrementAndGet);
        assertThat(refusedInvocations.get()).isZero();
        assertThat(row(PASS).skipped()).isEqualTo(1);

        // concurrent successful ticks on a CLOSED pass keep succeeded exact
        MaintenancePass concurrent = MaintenancePass.CONTACT_RETRY_SCHEDULED;
        int threads = 4;
        int perThread = 25;
        CountDownLatch startLine = new CountDownLatch(1);
        for (int thread = 0; thread < threads; thread++) {
            workers.execute(() -> {
                try {
                    startLine.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int tick = 0; tick < perThread; tick++) {
                    guard.run(concurrent, () -> {
                    });
                }
            });
        }
        startLine.countDown();
        workers.shutdown();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        MaintenancePassGuard.PassSnapshot counted = row(concurrent);
        assertThat(counted.succeeded()).isEqualTo((long) threads * perThread);
        assertThat(counted.succeeded() + counted.failed() + counted.skipped())
                .isEqualTo((long) threads * perThread);
        assertThat(counted.state()).isEqualTo(CircuitState.CLOSED);
    }

    /** Drives {@code pass} to OPEN with the default failure threshold of three. */
    private void quarantine(MaintenancePass pass) {
        for (int tick = 0; tick < MaintenanceSchedulingProperties.DEFAULT_PASS_FAILURE_THRESHOLD; tick++) {
            assertThatThrownBy(() -> guard.run(pass, () -> {
                throw new IllegalStateException("pass " + pass + " failed");
            })).isInstanceOf(IllegalStateException.class);
        }
    }

    private MaintenancePassGuard.PassSnapshot row(MaintenancePass pass) {
        return guard.snapshot().stream()
                .filter(row -> row.pass() == pass)
                .findFirst()
                .orElseThrow();
    }

    /** Deterministic clock: only the test moves time. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
