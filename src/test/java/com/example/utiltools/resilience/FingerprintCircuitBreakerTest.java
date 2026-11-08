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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-fingerprint isolation of the {@link CircuitBreaker}: one machine per fingerprint, quarantine
 * rows naming the isolated key, and independent probe leases.
 */
class FingerprintCircuitBreakerTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private static final Duration PROBE_LEASE = Duration.ofSeconds(10);
    private static final String A = "A";
    private static final String B = "B";

    private TestClock clock;
    private AuditTrail auditTrail;
    private CircuitBreaker breaker;
    private ExecutorService worker;

    @BeforeEach
    void setUp() {
        clock = new TestClock(START);
        auditTrail = new AuditTrail(64, clock);
        breaker = new CircuitBreaker("json-query", new CircuitBreakerConfig(3, 2, COOLDOWN, PROBE_LEASE),
                auditTrail, clock);
        worker = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    // ---- REQ-01 / REQ-04 / REQ-05(a) ------------------------------------------------------

    @Test
    @DisplayName("A failing fingerprint trips CLOSED -> OPEN without affecting the others")
    void shouldQuarantineOnlyFailingFingerprint() {
        trip(A);

        assertThat(breaker.state(A)).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.consecutiveFailures(A)).isEqualTo(3);
        assertThat(breaker.resetAt(A)).isEqualTo(START.plus(COOLDOWN));

        assertThat(breaker.state(B)).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.consecutiveFailures(B)).isZero();
        assertThat(breaker.consecutiveSuccesses(B)).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("With A open, a guarded call for A fast-fails while B still runs its delegate")
    void shouldFastFailOnlyForQuarantinedFingerprint() {
        trip(A);
        int rowsWhileOpen = auditTrail.size();
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> breaker.execute(A, () -> {
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

        assertThat(breaker.execute(B, () -> "b-value")).isEqualTo("b-value");
        assertThat(breaker.consecutiveSuccesses(B)).isEqualTo(1);
        assertThat(breaker.state(B)).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.consecutiveSuccesses(A)).isZero();
    }

    // ---- REQ-02 / REQ-05(d) ---------------------------------------------------------------

    @Test
    @DisplayName("Isolating a fingerprint records a QUARANTINE row that names it, after the transition row")
    void shouldRecordQuarantineEntryWithFingerprint() {
        trip(A);

        List<AuditEntry> recent = auditTrail.recent(2);
        AuditEntry quarantine = recent.get(0);
        assertThat(quarantine.trigger()).isEqualTo(CircuitTrigger.QUARANTINE);
        assertThat(quarantine.fingerprint()).isEqualTo(A);
        assertThat(quarantine.breaker()).isEqualTo("json-query");
        assertThat(quarantine.from()).isEqualTo(CircuitState.CLOSED);
        assertThat(quarantine.to()).isEqualTo(CircuitState.OPEN);
        assertThat(quarantine.reason()).isEqualTo("fingerprint quarantined: failure threshold reached");

        AuditEntry transition = recent.get(1);
        assertThat(transition.trigger()).isEqualTo(CircuitTrigger.FAILURE_THRESHOLD_REACHED);
        assertThat(transition.fingerprint()).isEqualTo(A);
    }

    @Test
    @DisplayName("A failed HALF_OPEN probe re-quarantines the fingerprint with its own QUARANTINE row")
    void shouldRecordQuarantineEntryWhenProbeFails() {
        trip(A);
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThatThrownBy(() -> breaker.execute(A, () -> failing(A)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(breaker.state(A)).isEqualTo(CircuitState.OPEN);

        List<AuditEntry> recent = auditTrail.recent(2);
        assertThat(recent.get(0).trigger()).isEqualTo(CircuitTrigger.QUARANTINE);
        assertThat(recent.get(0).fingerprint()).isEqualTo(A);
        assertThat(recent.get(0).from()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(recent.get(0).to()).isEqualTo(CircuitState.OPEN);
        assertThat(recent.get(1).trigger()).isEqualTo(CircuitTrigger.PROBE_FAILED);
        assertThat(recent.get(1).fingerprint()).isEqualTo(A);
        assertThat(countRows(CircuitTrigger.QUARANTINE, B)).isZero();
    }

    // ---- REQ-05(b) -----------------------------------------------------------------------

    @Test
    @DisplayName("A successful probe after the cooldown clears the quarantine of that fingerprint only")
    void shouldClearQuarantineAfterCooldownProbeSucceeds() {
        trip(A);
        assertThat(view(A).quarantined()).isTrue();
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThat(breaker.probe(A, () -> "ok").probeSucceeded()).isTrue();
        assertThat(breaker.probe(A, () -> "ok").probeSucceeded()).isTrue();

        assertThat(breaker.state(A)).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.resetAt(A)).isNull();
        CircuitBreaker.FingerprintStateView viewA = view(A);
        assertThat(viewA.quarantined()).isFalse();
        assertThat(viewA.consecutiveFailures()).isZero();
        assertThat(countRows(CircuitTrigger.PROBE_SUCCEEDED, A)).isEqualTo(1);

        assertThat(breaker.state(B)).isEqualTo(CircuitState.CLOSED);
        assertThat(countRows(CircuitTrigger.QUARANTINE, B)).isZero();
    }

    // ---- REQ-05(c) -----------------------------------------------------------------------

    @Test
    @DisplayName("Lease expiry and reapExpiredProbe operate per fingerprint")
    void shouldReapExpiredProbePerFingerprint() {
        trip(A);
        trip(B);
        clock.advance(COOLDOWN.plusSeconds(1));

        BlockedCall probeB = new BlockedCall(B);
        probeB.start();
        clock.advance(Duration.ofSeconds(6));
        BlockedCall probeA = new BlockedCall(A);
        probeA.start();
        clock.advance(Duration.ofSeconds(5));

        assertThat(breaker.probeInFlight(A)).isTrue();
        assertThat(breaker.probeInFlight(B)).isFalse();
        assertThat(breaker.probeLeaseExpiresAt(A)).isEqualTo(clock.instant().plusSeconds(5));

        assertThat(breaker.reapExpiredProbe(A)).isFalse();
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED, A)).isZero();

        assertThat(breaker.reapExpiredProbe()).isTrue();
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED, B)).isEqualTo(1);
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED, A)).isZero();
        assertThat(breaker.reapExpiredProbe()).isFalse();
        assertThat(breaker.reapExpiredProbe(B)).isFalse();

        clock.advance(Duration.ofSeconds(6));
        assertThat(breaker.reapExpiredProbe(A)).isTrue();
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED, A)).isEqualTo(1);
        assertThat(breaker.reapExpiredProbe(A)).isFalse();
        assertThat(countRows(CircuitTrigger.PROBE_LEASE_EXPIRED, A)).isEqualTo(1);

        probeA.releaseAndJoin();
        probeB.releaseAndJoin();
    }

    // ---- REQ-01 / REQ-02 legacy path -----------------------------------------------------

    @Test
    @DisplayName("The default fingerprint keeps today's row sequence and never records QUARANTINE")
    void shouldKeepDefaultFingerprintAuditTrailUnchanged() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> breaker.execute(() -> failing(Fingerprints.DEFAULT)))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(auditTrail.size()).isEqualTo(1);
        assertThat(auditTrail.recent(1).get(0).trigger()).isEqualTo(CircuitTrigger.FAILURE_THRESHOLD_REACHED);
        assertThat(auditTrail.recent(1).get(0).fingerprint()).isEqualTo(Fingerprints.DEFAULT);
        assertThat(countRows(CircuitTrigger.QUARANTINE)).isZero();

        clock.advance(COOLDOWN.plusSeconds(1));
        CircuitBreaker.ProbeResult result = breaker.probe(() -> failing(Fingerprints.DEFAULT));

        assertThat(result.probeSucceeded()).isFalse();
        assertThat(result.fingerprint()).isEqualTo(Fingerprints.DEFAULT);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        List<AuditEntry> recent = auditTrail.recent(2);
        assertThat(recent.get(0).trigger()).isEqualTo(CircuitTrigger.PROBE_FAILED);
        assertThat(recent.get(1).trigger()).isEqualTo(CircuitTrigger.COOLDOWN_ELAPSED);
        assertThat(countRows(CircuitTrigger.QUARANTINE)).isZero();
    }

    @Test
    @DisplayName("admit() normalises the key and the legacy execute path shares the default machine")
    void shouldResolveLegacyPathToDefaultFingerprint() {
        CircuitBreaker.Admission admission = breaker.admit(null);

        assertThat(admission.fingerprint()).isEqualTo(Fingerprints.DEFAULT);
        assertThat(admission.generation()).isZero();

        assertThat(breaker.execute(() -> "legacy")).isEqualTo("legacy");
        assertThat(breaker.consecutiveSuccesses()).isEqualTo(1);
        assertThat(breaker.consecutiveSuccesses(Fingerprints.DEFAULT)).isEqualTo(1);

        assertThat(Fingerprints.normalize("client-1")).isEqualTo("client-1");
        assertThat(Fingerprints.normalize("  spaced.key:1  ")).isEqualTo("spaced.key:1");
        assertThat(Fingerprints.normalize("bad key!")).isEqualTo(Fingerprints.DEFAULT);
        assertThat(Fingerprints.normalize("")).isEqualTo(Fingerprints.DEFAULT);
        assertThat(Fingerprints.normalize("x".repeat(65))).isEqualTo(Fingerprints.DEFAULT);
        assertThat(breaker.state(null)).isEqualTo(CircuitState.CLOSED);
    }

    // ---- REQ-01 memory bound -------------------------------------------------------------

    @Test
    @DisplayName("The fingerprint map stays bounded, evicting CLOSED buckets before quarantined ones")
    void shouldBoundFingerprintStateMap() {
        CircuitBreaker bounded = new CircuitBreaker("json-query",
                new CircuitBreakerConfig(1, 1, COOLDOWN, PROBE_LEASE), new AuditTrail(64, clock), clock, 2);

        assertThatThrownBy(() -> bounded.execute(A, () -> failing(A))).isInstanceOf(IllegalStateException.class);
        assertThat(bounded.state(A)).isEqualTo(CircuitState.OPEN);

        assertThat(bounded.execute("c1", () -> "ok")).isEqualTo("ok");
        assertThat(bounded.fingerprintStates()).hasSize(2);

        assertThat(bounded.execute("c2", () -> "ok")).isEqualTo("ok");
        assertThat(bounded.fingerprintStates())
                .extracting(CircuitBreaker.FingerprintStateView::fingerprint)
                .containsExactly(A, "c2");
        assertThat(bounded.state(A)).isEqualTo(CircuitState.OPEN);

        assertThat(bounded.state("ghost")).isEqualTo(CircuitState.CLOSED);
        assertThat(bounded.consecutiveFailures("ghost")).isZero();
        assertThat(bounded.resetAt("ghost")).isNull();
        assertThat(bounded.probeLeaseExpiresAt("ghost")).isNull();
        assertThat(bounded.fingerprintStates()).hasSize(2);

        for (int i = 0; i < 20; i++) {
            assertThat(bounded.execute("k" + i, () -> "ok")).isEqualTo("ok");
            assertThat(bounded.fingerprintStates().size()).isLessThanOrEqualTo(bounded.maxFingerprints());
        }
        assertThat(bounded.fingerprintStates()).hasSize(2);
    }

    // ---- helpers -------------------------------------------------------------------------

    private long countRows(CircuitTrigger trigger) {
        return auditTrail.recent(auditTrail.capacity()).stream()
                .filter(entry -> entry.trigger() == trigger)
                .count();
    }

    private long countRows(CircuitTrigger trigger, String fingerprint) {
        return auditTrail.recent(auditTrail.capacity()).stream()
                .filter(entry -> entry.trigger() == trigger)
                .filter(entry -> entry.fingerprint().equals(fingerprint))
                .count();
    }

    private CircuitBreaker.FingerprintStateView view(String fingerprint) {
        return breaker.fingerprintStates().stream()
                .filter(view -> view.fingerprint().equals(fingerprint))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no state view for fingerprint " + fingerprint));
    }

    private void trip(String fingerprint) {
        for (int i = 0; i < 3; i++) {
            try {
                breaker.execute(fingerprint, () -> failing(fingerprint));
            } catch (IllegalStateException expected) {
                // expected: the delegate's own exception is rethrown
            }
        }
        assertThat(breaker.state(fingerprint)).isEqualTo(CircuitState.OPEN);
    }

    private String failing(String fingerprint) {
        throw new IllegalStateException("boom " + fingerprint);
    }

    /** A guarded call that is observably inside the breaker until the test releases it. */
    private final class BlockedCall {

        private final String fingerprint;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Future<?> future;

        BlockedCall(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        void start() {
            future = worker.submit(() -> {
                try {
                    breaker.execute(fingerprint, () -> {
                        entered.countDown();
                        await(release);
                        return "late";
                    });
                } catch (RuntimeException expected) {
                    // a superseded claim is silently ignored; the state assertions prove it
                }
                return null;
            });
            await(entered);
        }

        void releaseAndJoin() {
            release.countDown();
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("blocked call did not finish", e);
            }
        }

        private void await(CountDownLatch latch) {
            try {
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the guarded call", e);
            }
        }
    }

    static final class TestClock extends Clock {

        private Instant instant;

        TestClock(Instant start) {
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
