package com.example.utiltools.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private MutableClock clock;
    private AuditTrail auditTrail;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        auditTrail = new AuditTrail(16, clock);
        breaker = new CircuitBreaker("json-query", new CircuitBreakerConfig(3, 2, COOLDOWN), auditTrail, clock);
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
