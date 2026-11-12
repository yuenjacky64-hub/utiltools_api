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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bounded regex evaluation budget for {@code POST /api/v1/utils/text/replace} (REQ-02, DOD-01/DOD-02).
 * The timeout is driven deterministically by a stepping {@link Clock} — no sleeps.
 */
class TextServiceRegexTimeoutTest {

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

    private static TextService serviceWithSteppingClock() {
        return new TextService(200, new SteppingClock(0L, 1L));
    }

    private static TextReplaceRequest replace(String text, String pattern, String replacement,
                                              Boolean all, Boolean literal) {
        return new TextReplaceRequest(text, pattern, replacement, all, literal, null);
    }

    @Test
    @DisplayName("catastrophic backtracking aborts at the deadline and completes well under a second (DOD-01)")
    void shouldAbortCatastrophicBacktrackingAtTheSharedDeadline() {
        TextService service = serviceWithSteppingClock();
        String text = "a".repeat(500) + "b";
        long startedNanos = System.nanoTime();

        assertThatThrownBy(() -> service.replace(replace(text, "(a+)+$", "X", null, null)))
                .isInstanceOf(RegexTimeoutException.class)
                .hasMessageContaining("timed out after 200 ms");

        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        assertThat(elapsedMillis)
                .as("deterministic stepping clock must bound the abort well under a second")
                .isLessThan(1_000L);
    }

    @Test
    @DisplayName("literal=true and all=false complete within budget and keep their documented semantics")
    void shouldKeepLiteralAndFirstOnlySemanticsWithinBudget() {
        TextService service = serviceWithSteppingClock();

        TextReplaceResponse literal = service.replace(replace("a a", "a", "-", null, true));
        assertThat(literal.replacements()).isEqualTo(2);

        TextReplaceResponse firstOnly = service.replace(replace("a a", "a", "-", false, null));
        assertThat(firstOnly.result()).isEqualTo("- a");
        assertThat(firstOnly.replacements()).isEqualTo(1);
    }

    @Test
    @DisplayName("the configured budget is validated to 1..60000 at construction (REQ-01)")
    void shouldRejectOutOfRangeRegexTimeout() {
        Clock clock = Clock.systemUTC();

        assertThatThrownBy(() -> new TextService(0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..60000");
        assertThatThrownBy(() -> new TextService(60_001, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..60000");
        assertThatCode(() -> new TextService(1, clock)).doesNotThrowAnyException();
        assertThatCode(() -> new TextService(60_000, clock)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("two simultaneous replaces keep independent deadlines: the pathological call aborts, the ordinary one completes (DOD-02)")
    void shouldKeepDeadlinesIndependentPerRequest() throws Exception {
        TextService pathologicalService = serviceWithSteppingClock();
        TextService ordinaryService = new TextService(200, Clock.systemUTC());

        Callable<Object> pathological = () ->
                pathologicalService.replace(replace("a".repeat(500) + "b", "(a+)+$", "X", null, null));
        Callable<TextReplaceResponse> ordinary = () ->
                ordinaryService.replace(replace("hello hello", "hello", "world", null, null));

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Object> abort = pool.submit(pathological);
            Future<TextReplaceResponse> normal = pool.submit(ordinary);

            assertThatThrownBy(abort::get)
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(RegexTimeoutException.class);
            assertThat(normal.get()).isEqualTo(
                    new TextReplaceResponse("world world", 2, true, "world world".length()));
        }
    }
}
