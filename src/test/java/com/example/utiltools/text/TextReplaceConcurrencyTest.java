package com.example.utiltools.text;

import com.example.utiltools.text.dto.TextReplaceRequest;
import com.example.utiltools.text.dto.TextReplaceResponse;
import com.example.utiltools.text.exception.RegexTimeoutException;
import com.example.utiltools.text.service.TextService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Independent-deadline concurrency guarantee for {@code POST /api/v1/utils/text/replace}
 * (REQ-02, DOD-02). Two simultaneous {@code replace} calls must not share budget state: the
 * catastrophic call aborts with {@link RegexTimeoutException} while the ordinary call completes
 * with its exact expected {@link TextReplaceResponse}. The abort is driven deterministically by a
 * stepping {@link Clock} so the test never sleeps on a pathological regex — proving A's per-call
 * deadline/cadence counter does not leak into B.
 */
class TextReplaceConcurrencyTest {

    /** Clock that advances exactly one configured step on every {@code millis()} read. */
    private static final class SteppingClock extends Clock {
        private final long stepMs;
        private long nowMs;

        SteppingClock(long startMs, long stepMs) {
            this.nowMs = startMs;
            this.stepMs = stepMs;
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
            return Instant.ofEpochMilli(nowMs);
        }

        @Override
        public long millis() {
            nowMs += stepMs;
            return nowMs;
        }
    }

    private static TextReplaceRequest replace(String text, String pattern, String replacement) {
        return new TextReplaceRequest(text, pattern, replacement, null, null, null);
    }

    @Test
    @DisplayName("a catastrophic replace aborts while a simultaneous ordinary replace completes (DOD-02)")
    void shouldKeepDeadlinesIndependentPerRequest() throws Exception {
        TextService pathologicalService = new TextService(200, new SteppingClock(0L, 1L));
        TextService ordinaryService = new TextService(200, Clock.systemUTC());

        // Both calls are released together so their deadlines genuinely overlap; each service keeps
        // its own per-call budget state (pathological one on the stepping clock, ordinary one on the
        // system clock).
        CyclicBarrier release = new CyclicBarrier(2);
        Callable<Object> pathological = () -> {
            release.await();
            return pathologicalService.replace(replace("a".repeat(500) + "b", "(a+)+$", "X"));
        };
        Callable<Object> ordinary = () -> {
            release.await();
            return ordinaryService.replace(replace("hello hello", "hello", "world"));
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> abort = pool.submit(pathological);
            Future<Object> normal = pool.submit(ordinary);

            assertThatThrownBy(abort::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RegexTimeoutException.class);
            assertThat(normal.get())
                    .as("the ordinary call is unaffected by the pathological call's deadline")
                    .isEqualTo(new TextReplaceResponse("world world", 2, true, 11));
        } finally {
            pool.shutdownNow();
        }
    }
}
