package com.example.utiltools.text;

import com.example.utiltools.text.dto.TextSimilarityRequest;
import com.example.utiltools.text.dto.TextSimilarityResponse;
import com.example.utiltools.text.dto.TokenizerScheme;
import com.example.utiltools.text.exception.RegexTimeoutException;
import com.example.utiltools.text.service.TextService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bean-validation constraints and the optional per-request regex budget of
 * {@code POST /api/v1/utils/text/similarity} (REQ-01, REQ-02, REQ-03; DOD-01/DOD-02/DOD-05). Every
 * assertion pins an exact validator or exception message so a stubbed body cannot pass.
 */
class TextSimilarityValidationTimeoutTest {

    private static final String PATTERN_TOO_LONG = "pattern must be at most 512 characters";

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

    private static List<String> messages(Validator validator, TextSimilarityRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getMessage())
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("similarity request - pattern is capped at 512 characters for the regex scheme only and timeoutMs "
            + "is bounded to 0..60000 (REQ-01, REQ-02 / DOD-06)")
    void similarityRequestPatternAndTimeoutConstraints() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            // 512 characters is the inclusive boundary: still valid.
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.REGEX, "a".repeat(512), null, null))).isEmpty();

            // 513 characters is one violation carrying the pinned message.
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.REGEX, "a".repeat(513), null, null)))
                    .containsExactly(PATTERN_TOO_LONG);

            // The cap is regex-only: a predefined tokenizer ignores the pattern, so no length is a violation.
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, "a".repeat(513), null, null))).isEmpty();
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, "a".repeat(600), null, null))).isEmpty();

            // timeoutMs is optional: absent, explicit null, zero and the configured ceiling are all valid.
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, null))).isEmpty();
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, null, null, null))).isEmpty();
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, null, null, 0))).isEmpty();
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, null, null, 60000))).isEmpty();

            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.REGEX, "(a+)+$", null, -1)))
                    .containsExactly("timeoutMs must be at least 0");
            assertThat(messages(validator, new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.REGEX, "(a+)+$", null, 60001)))
                    .containsExactly("timeoutMs must be at most 60000");
        }
    }

    @Test
    @DisplayName("similarity request - a positive timeoutMs is applied and clamped to the configured ceiling "
            + "(REQ-02, REQ-03 / DOD-02)")
    void similarityRequestTimeoutBoundPatternEvaluation() {
        TextService budgeted = new TextService(200, new SteppingClock(0L, 1000L));
        String backtracking = "a".repeat(1999) + "!";

        // 5 ms is below the configured 200 ms, so the request budget is what the caller observes.
        assertThatThrownBy(() -> budgeted.similarity(new TextSimilarityRequest(backtracking, "abc", null,
                TokenizerScheme.REGEX, "(a+)+$", null, 5)))
                .isInstanceOf(RegexTimeoutException.class)
                .hasMessage("regex evaluation timed out after 5 ms");

        // 10000 ms is above the configured ceiling: the clamp keeps the effective budget at 200 ms.
        assertThatThrownBy(() -> budgeted.similarity(new TextSimilarityRequest(backtracking, "abc", null,
                TokenizerScheme.REGEX, "(a+)+$", null, 10_000)))
                .isInstanceOf(RegexTimeoutException.class)
                .hasMessage("regex evaluation timed out after 200 ms");

        // Absent and zero are the pre-change path: identical results to the 5-arg form.
        TextSimilarityResponse fiveArg = new TextService().similarity(new TextSimilarityRequest(
                "kitten", "sitting", null, TokenizerScheme.REGEX, "[a-z]+"));
        TextSimilarityResponse absent = new TextService().similarity(new TextSimilarityRequest(
                "kitten", "sitting", null, TokenizerScheme.REGEX, "[a-z]+", null, null));
        TextSimilarityResponse zero = new TextService().similarity(new TextSimilarityRequest(
                "kitten", "sitting", null, TokenizerScheme.REGEX, "[a-z]+", null, 0));

        assertThat(absent).isEqualTo(fiveArg);
        assertThat(zero).isEqualTo(fiveArg);
        assertThat(absent.distance()).isEqualTo(3);
        assertThat(absent.maxLength()).isEqualTo(7);
    }
}
