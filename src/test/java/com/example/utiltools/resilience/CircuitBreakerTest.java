package com.example.utiltools.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private static final Duration PROBE_LEASE = Duration.ofSeconds(10);

    private MutableClock clock;
    private AuditTrail auditTrail;
    private CircuitBreaker breaker;
    private ExecutorService worker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        auditTrail = new AuditTrail(16, clock);
        breaker = new CircuitBreaker("json-query", new CircuitBreakerConfig(3, 2, COOLDOWN, PROBE_LEASE),
                auditTrail, clock);
        worker = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    @Test
    @DisplayName("N consecutive failures trip CLOSED -> OPEN and record FAILURE_THRESHOLD_REACHED")
    void shouldTripToOpenAfterThresholdFailures() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.execute(this::failingCall))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.consecutiveFailures()).isEqualTo(3);
        assertThat(auditTrail.size()).isEqualTo(1);
        assertThat(auditTrail.recent(1).get(0).trigger()).isEqualTo(CircuitTrigger.FAILURE_THRESHOLD_REACHED);
        assertThat(breaker.resetAt()).isEqualTo(START.plus(COOLDOWN));
    }

    @Test
    @DisplayName("While OPEN, execute fast-fails without invoking the delegate and without an audit row")
    void shouldFastFailWithCircuitOpenExceptionWhenOpen() {
        tripBreaker();
        int rowsWhileOpen = auditTrail.size();
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> breaker.execute(() -> {
            invocations.incrementAndGet();
            return "unreachable";
        }))
                .isInstanceOf(CircuitBreakerOpenException.class)
                .satisfies(thrown -> {
                    CircuitBreakerOpenException ex = (CircuitBreakerOpenException) thrown;
                    assertThat(ex.state()).isEqualTo(CircuitState.OPEN);
                    assertThat(ex.reason()).isNotBlank();
                    assertThat(ex.getMessage()).isEqualTo("Service unavailable: circuit breaker is open");
                });

        assertThat(invocations.get()).isZero();
        assertThat(auditTrail.size()).isEqualTo(rowsWhileOpen);
    }

    @Test
    @DisplayName("The next call after the cooldown transitions OPEN -> HALF_OPEN with COOLDOWN_ELAPSED")
    void shouldTransitionToHalfOpenAfterCooldown() {
        tripBreaker();
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThat(breaker.execute(() -> "ok")).isEqualTo("ok");

        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(auditTrail.recent(1).get(0).trigger()).isEqualTo(CircuitTrigger.COOLDOWN_ELAPSED);
    }

    @Test
    @DisplayName("A failed HALF_OPEN probe returns to OPEN and is recorded with trigger PROBE_FAILED")
    void shouldReturnToOpenWhenHalfOpenProbeFails() {
        tripBreaker();
        Instant resetAtWhileOpen = breaker.resetAt();
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThatThrownBy(() -> breaker.execute(this::failingCall))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(auditTrail.recent(1).get(0).trigger()).isEqualTo(CircuitTrigger.PROBE_FAILED);
        assertThat(breaker.resetAt()).isNotNull().isAfter(resetAtWhileOpen);
    }

    @Test
    @DisplayName("Reaching the success threshold in HALF_OPEN closes the breaker with PROBE_SUCCEEDED")
    void shouldCloseWhenHalfOpenProbeSucceeds() {
        tripBreaker();
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThat(breaker.execute(() -> "first")).isEqualTo("first");
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);

        assertThat(breaker.execute(() -> "second")).isEqualTo("second");

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.resetAt()).isNull();
        assertThat(breaker.consecutiveFailures()).isZero();
        assertThat(auditTrail.recent(1).get(0).trigger()).isEqualTo(CircuitTrigger.PROBE_SUCCEEDED);
    }

    // ---- probe lease / generation fencing ------------------------------------------------

    @Test
    @DisplayName("A second caller inside a live probe lease gets 503 CIRCUIT_OPEN 'probe already in flight'")
    void shouldFastFailSecondCallerWhileProbeLeaseIsLive() {
        BlockedProbe probe = startBlockedProbe("late");
        int rowsDuringLease = auditTrail.size();

        assertThat(breaker.probeInFlight()).isTrue();
        assertThat(breaker.probeLeaseExpiresAt()).isNotNull();
        assertThatThrownBy(() -> breaker.execute(() -> "second"))
                .isInstanceOf(CircuitBreakerOpenException.class)
                .satisfies(thrown -> {
                    CircuitBreakerOpenException ex = (CircuitBreakerOpenException) thrown;
                    assertThat(ex.state()).isEqualTo(CircuitState.HALF_OPEN);
                    assertThat(ex.reason()).contains("probe already in flight");
                });
        assertThat(auditTrail.size()).isEqualTo(rowsDuringLease);

        assertThat(probe.releaseAndGet()).isEqualTo("late");
    }

    @Test
    @DisplayName("After the probe lease expires a new probe is admitted with exactly one PROBE_LEASE_EXPIRED row")
    void shouldAdmitNewProbeAfterExpiredLease() {
        BlockedProbe probe = startBlockedProbe("late");

        clock.advance(PROBE_LEASE.plusSeconds(1));

        assertThat(breaker.execute(() -> "ok")).isEqualTo("ok");
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED)).isEqualTo(1);
        assertThat(breaker.reapExpiredProbe()).isFalse();
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED)).isEqualTo(1);

        assertThat(probe.releaseAndGet()).isEqualTo("late");
    }

    @Test
    @DisplayName("A late success from a superseded probe generation is ignored")
    void shouldIgnoreLateSuccessFromSupersededGeneration() {
        BlockedProbe probe = startBlockedProbe("late");

        clock.advance(PROBE_LEASE.plusSeconds(1));
        assertThat(breaker.reapExpiredProbe()).isTrue();
        assertThat(breaker.execute(() -> "current")).isEqualTo("current");

        CircuitState stateBefore = breaker.state();
        int failuresBefore = breaker.consecutiveFailures();
        int successesBefore = breaker.consecutiveSuccesses();
        int rowsBefore = auditTrail.size();

        assertThat(probe.releaseAndGet()).isEqualTo("late");

        assertThat(breaker.state()).isEqualTo(stateBefore);
        assertThat(breaker.consecutiveFailures()).isEqualTo(failuresBefore);
        assertThat(breaker.consecutiveSuccesses()).isEqualTo(successesBefore);
        assertThat(auditTrail.size()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("A late failure from a superseded probe generation is ignored")
    void shouldIgnoreLateFailureFromSupersededGeneration() {
        BlockedProbe probe = startBlockedProbe(new IllegalStateException("late failure"));

        clock.advance(PROBE_LEASE.plusSeconds(1));
        assertThat(breaker.reapExpiredProbe()).isTrue();
        assertThat(breaker.execute(() -> "current")).isEqualTo("current");

        CircuitState stateBefore = breaker.state();
        int failuresBefore = breaker.consecutiveFailures();
        int successesBefore = breaker.consecutiveSuccesses();
        int rowsBefore = auditTrail.size();

        assertThat(probe.releaseAndGetFailure()).hasMessageContaining("late failure");

        assertThat(breaker.state()).isEqualTo(stateBefore);
        assertThat(breaker.consecutiveFailures()).isEqualTo(failuresBefore);
        assertThat(breaker.consecutiveSuccesses()).isEqualTo(successesBefore);
        assertThat(auditTrail.size()).isEqualTo(rowsBefore);
        assertThat(countRows(CircuitTrigger.PROBE_FAILED)).isZero();
    }

    @Test
    @DisplayName("reapExpiredProbe() releases only an expired claim, exactly once")
    void shouldReapExpiredProbeIdempotently() {
        // CLOSED and OPEN: nothing to reap.
        assertThat(breaker.reapExpiredProbe()).isFalse();
        tripBreaker();
        assertThat(breaker.reapExpiredProbe()).isFalse();

        // HALF_OPEN with a live lease: a running probe is never released.
        clock.advance(COOLDOWN.plusSeconds(1));
        BlockedProbe probe = startBlockedProbe("late");
        int rowsWhileLive = auditTrail.size();
        assertThat(breaker.probeInFlight()).isTrue();
        assertThat(breaker.reapExpiredProbe()).isFalse();
        assertThat(auditTrail.size()).isEqualTo(rowsWhileLive);

        // HALF_OPEN with an expired lease: released exactly once.
        clock.advance(PROBE_LEASE.plusSeconds(1));
        assertThat(breaker.reapExpiredProbe()).isTrue();
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED)).isEqualTo(1);
        assertThat(breaker.probeInFlight()).isFalse();
        assertThat(breaker.probeLeaseExpiresAt()).isNull();
        assertThat(breaker.reapExpiredProbe()).isFalse();
        assertThat(breaker.reapExpiredProbe()).isFalse();
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED)).isEqualTo(1);

        assertThat(probe.releaseAndGet()).isEqualTo("late");
    }

    @Test
    @DisplayName("A nested guarded call inside a probe passes through and counts once")
    void shouldCountNestedGuardedCallOnceInProbe() {
        tripBreaker();
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThat(breaker.execute(() -> breaker.execute(() -> "nested"))).isEqualTo("nested");

        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.consecutiveSuccesses()).isEqualTo(1);
    }

    @Test
    @DisplayName("probeLease defaults to 10s and the 3-argument constructor still delegates")
    void shouldDefaultProbeLeaseToTenSecondsAndKeepThreeArgConstructor() {
        assertThat(CircuitBreakerConfig.defaults().probeLease()).isEqualTo(Duration.ofSeconds(10));
        assertThat(new CircuitBreakerConfig(3, 2, COOLDOWN).probeLease()).isEqualTo(Duration.ofSeconds(10));
        assertThat(new CircuitBreakerConfig(3, 2, COOLDOWN, PROBE_LEASE).probeLease()).isEqualTo(PROBE_LEASE);

        assertThatThrownBy(() -> new CircuitBreakerConfig(3, 2, COOLDOWN, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig(3, 2, COOLDOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Trips the breaker, waits out the cooldown and blocks a probe thread inside HALF_OPEN. */
    private BlockedProbe startBlockedProbe(String result) {
        return startBlockedProbe(() -> result);
    }

    private BlockedProbe startBlockedProbe(RuntimeException failure) {
        return startBlockedProbe(() -> {
            throw failure;
        });
    }

    private BlockedProbe startBlockedProbe(ProbeBody body) {
        if (breaker.state() == CircuitState.CLOSED) {
            tripBreaker();
            clock.advance(COOLDOWN.plusSeconds(1));
        }
        BlockedProbe probe = new BlockedProbe(body);
        probe.start();
        return probe;
    }

    private long countRows(CircuitTrigger trigger) {
        return auditTrail.recent(auditTrail.capacity()).stream()
                .filter(entry -> entry.trigger() == trigger)
                .count();
    }

    private void tripBreaker() {
        for (int i = 0; i < 3; i++) {
            try {
                breaker.execute(this::failingCall);
            } catch (IllegalStateException expected) {
                // expected: the delegate's own exception is rethrown
            }
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
    }

    private String failingCall() {
        throw new IllegalStateException("boom");
    }

    @FunctionalInterface
    private interface ProbeBody {
        String run();
    }

    /** A probe delegate that is observably inside the breaker before the test proceeds. */
    private final class BlockedProbe {

        private final ProbeBody body;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Throwable> thrown = new AtomicReference<>();
        private Future<?> future;
        private String result;

        BlockedProbe(ProbeBody body) {
            this.body = body;
        }

        void start() {
            future = worker.submit(() -> {
                try {
                    result = breaker.execute(() -> {
                        entered.countDown();
                        await(release);
                        return body.run();
                    });
                } catch (Throwable t) {
                    thrown.set(t);
                }
                return null;
            });
            await(entered);
        }

        String releaseAndGet() {
            release.countDown();
            join();
            assertThat(thrown.get()).isNull();
            return result;
        }

        Throwable releaseAndGetFailure() {
            release.countDown();
            join();
            assertThat(thrown.get()).isNotNull();
            return thrown.get();
        }

        private void join() {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("probe thread did not finish", e);
            }
        }

        private void await(CountDownLatch latch) {
            try {
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the probe thread", e);
            }
        }
    }

    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
