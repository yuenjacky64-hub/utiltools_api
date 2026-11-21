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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tokenizer schemes for {@code POST /api/v1/utils/text/similarity} (REQ-01, REQ-02; DOD-01/DOD-02,
 * DOD-06, DOD-08/DOD-09/DOD-10, DOD-13). Every number below is the code-point Levenshtein value the
 * joined token sequences produce — no clock, no locale and no sleeps.
 */
class TextSimilarityTokenizerTest {

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

    private final TextService service = new TextService();

    private TextSimilarityResponse similarity(String left, String right, Boolean caseSensitive,
            TokenizerScheme tokenizer, String pattern) {
        return service.similarity(new TextSimilarityRequest(left, right, caseSensitive, tokenizer, pattern));
    }

    @Test
    @DisplayName("similarity - every tokenizer scheme selects its own tokens (DOD-02)")
    void shouldApplyEachTokenizerScheme() {
        // letter is exactly TextService.tokenize: "ab12" and "12ab" are one whole letter/digit run each,
        // so the distance is the four code-point substitutions.
        TextSimilarityResponse letterMixed = similarity("ab12", "12ab", null, TokenizerScheme.LETTER, null);
        assertThat(letterMixed.distance()).isEqualTo(4);
        assertThat(letterMixed.maxLength()).isEqualTo(4);
        assertThat(letterMixed.similarity()).isEqualTo(0.0);
        assertThat(letterMixed.identical()).isFalse();

        // digit keeps digit runs only: both sides reduce to "12".
        TextSimilarityResponse digitMixed = similarity("ab12", "12ab", null, TokenizerScheme.DIGIT, null);
        assertThat(digitMixed.distance()).isZero();
        assertThat(digitMixed.maxLength()).isEqualTo(2);
        assertThat(digitMixed.similarity()).isEqualTo(1.0);
        assertThat(digitMixed.identical()).isTrue();

        // letter keeps "abc123" whole and joins "abc 123" as "abc 123", so the space is one insertion.
        TextSimilarityResponse letterSpaced = similarity("abc123", "abc 123", null, TokenizerScheme.LETTER, null);
        assertThat(letterSpaced.distance()).isEqualTo(1);
        assertThat(letterSpaced.maxLength()).isEqualTo(7);
        assertThat(letterSpaced.similarity()).isEqualTo(0.857143);

        // alphanum splits the letter/digit boundary, so both sides join to "abc 123".
        TextSimilarityResponse alphanumSpaced =
                similarity("abc123", "abc 123", null, TokenizerScheme.ALPHANUM, null);
        assertThat(alphanumSpaced.distance()).isZero();
        assertThat(alphanumSpaced.maxLength()).isEqualTo(7);
        assertThat(alphanumSpaced.similarity()).isEqualTo(1.0);

        // whitespace keeps punctuation inside the token, so "a.b" and "a b" differ.
        TextSimilarityResponse whitespaceSpaced = similarity("a.b", "a b", null, TokenizerScheme.WHITESPACE, null);
        assertThat(whitespaceSpaced.distance()).isEqualTo(1);
        assertThat(whitespaceSpaced.maxLength()).isEqualTo(3);
        assertThat(whitespaceSpaced.similarity()).isEqualTo(0.666667);

        // letter drops the separator between runs, so "a.b" and "a b" both tokenize to "a b".
        TextSimilarityResponse letterPunctuation = similarity("a.b", "a b", null, TokenizerScheme.LETTER, null);
        assertThat(letterPunctuation.distance()).isZero();
        assertThat(letterPunctuation.maxLength()).isEqualTo(3);
        assertThat(letterPunctuation.similarity()).isEqualTo(1.0);
        assertThat(letterPunctuation.identical()).isTrue();

        // letter is TextService.tokenize written out: letter/digit runs joined by one U+0020, so symbols
        // outside a run are not compared (whitespace, by contrast, keeps them inside its token).
        TextSimilarityResponse letterSupplementary =
                similarity("a\uD83D\uDE00b", "a\uD83D\uDE01b", null, TokenizerScheme.LETTER, null);
        assertThat(letterSupplementary.distance()).isZero();
        assertThat(letterSupplementary.maxLength()).isEqualTo(3);

        // regex selects the [a-z]+ runs and discards the digits on both sides.
        TextSimilarityResponse regexRuns =
                similarity("abc123def", "abc456def", null, TokenizerScheme.REGEX, "[a-z]+");
        assertThat(regexRuns.distance()).isZero();
        assertThat(regexRuns.maxLength()).isEqualTo(7);
        assertThat(regexRuns.similarity()).isEqualTo(1.0);
        assertThat(regexRuns.identical()).isTrue();

        // The same pair without the tokenizer keeps today's single-token behaviour.
        TextSimilarityResponse regexRunsDefault = similarity("abc123def", "abc456def", null, null, null);
        assertThat(regexRunsDefault.distance()).isEqualTo(3);
        assertThat(regexRunsDefault.maxLength()).isEqualTo(9);
        assertThat(regexRunsDefault.similarity()).isEqualTo(0.666667);
    }

    @Test
    @DisplayName("similarity - an absent tokenizer behaves exactly like letter (DOD-06)")
    void shouldDefaultToLetterScheme() {
        TextSimilarityResponse absent = similarity("ab-cd", "abcd", null, null, null);
        assertThat(absent.distance()).isEqualTo(1);
        assertThat(absent.maxLength()).isEqualTo(5);
        assertThat(absent.similarity()).isEqualTo(0.8);

        for (String wireName : new String[] {"letter", "LETTER", " letter "}) {
            TextSimilarityResponse named = similarity("ab-cd", "abcd", null,
                    TokenizerScheme.fromWire(wireName), null);
            assertThat(named.distance()).isEqualTo(absent.distance());
            assertThat(named.maxLength()).isEqualTo(absent.maxLength());
            assertThat(named.similarity()).isEqualTo(absent.similarity());
            assertThat(named.identical()).isEqualTo(absent.identical());
        }

        assertThat(TokenizerScheme.fromWire(null)).isEqualTo(TokenizerScheme.LETTER);
        assertThat(TokenizerScheme.fromWire("  ")).isEqualTo(TokenizerScheme.LETTER);
    }

    @Test
    @DisplayName("similarity - case folding happens before tokenization (DOD-08)")
    void shouldFoldBeforeTokenizing() {
        TextSimilarityResponse folded = similarity("ABC123", "abc123", null, TokenizerScheme.REGEX, "[a-z]+");
        assertThat(folded.distance()).isZero();
        assertThat(folded.maxLength()).isEqualTo(3);
        assertThat(folded.similarity()).isEqualTo(1.0);
        assertThat(folded.caseSensitive()).isFalse();

        TextSimilarityResponse sensitive =
                similarity("ABC123", "abc123", true, TokenizerScheme.REGEX, "[a-z]+");
        assertThat(sensitive.distance()).isEqualTo(3);
        assertThat(sensitive.maxLength()).isEqualTo(3);
        assertThat(sensitive.similarity()).isEqualTo(0.0);
        assertThat(sensitive.identical()).isFalse();
    }

    @Test
    @DisplayName("similarity - a scheme that selects nothing yields empty sequences, not a failure (DOD-09)")
    void shouldTreatNoTokensAsEmptySequence() {
        TextSimilarityResponse response = similarity("abc", "xyz", null, TokenizerScheme.DIGIT, null);

        assertThat(response.distance()).isZero();
        assertThat(response.maxLength()).isZero();
        assertThat(response.similarity()).isEqualTo(1.0);
        assertThat(response.identical()).isTrue();
    }

    @Test
    @DisplayName("similarity - pattern is ignored for every predefined scheme (DOD-10)")
    void shouldIgnorePatternForPredefinedSchemes() {
        TextSimilarityResponse withoutPattern = similarity("abc123", "abc456", null, TokenizerScheme.LETTER, null);
        TextSimilarityResponse withPattern =
                similarity("abc123", "abc456", null, TokenizerScheme.LETTER, "[0-9]+");

        assertThat(withPattern.distance()).isEqualTo(withoutPattern.distance());
        assertThat(withPattern.maxLength()).isEqualTo(withoutPattern.maxLength());
        assertThat(withPattern.similarity()).isEqualTo(withoutPattern.similarity());
        assertThat(withPattern.identical()).isEqualTo(withoutPattern.identical());
    }

    @Test
    @DisplayName("similarity - tokenizer binds case-insensitively and no bean validation touches pattern (DOD-01, DOD-06)")
    void shouldValidateTokenizerAndPatternFields() {
        assertThat(TokenizerScheme.fromWire(" digit ")).isEqualTo(TokenizerScheme.DIGIT);
        assertThat(TokenizerScheme.fromWire("ALPHANUM")).isEqualTo(TokenizerScheme.ALPHANUM);
        assertThat(TokenizerScheme.fromWire("WhiteSpace")).isEqualTo(TokenizerScheme.WHITESPACE);
        assertThat(TokenizerScheme.fromWire("regex")).isEqualTo(TokenizerScheme.REGEX);
        assertThatThrownBy(() -> TokenizerScheme.fromWire("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported tokenizer");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, null))).isEmpty();

            // REQ-01: pattern carries no bean-validation constraint, so neither an empty value nor an
            // over-long one for the regex scheme is a @Valid failure any more...
            assertThat(validator.validate(new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.REGEX, ""))).isEmpty();

            assertThat(validator.validate(new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.REGEX, "a".repeat(513)))).isEmpty();

            assertThat(validator.validate(new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.REGEX, "a".repeat(512)))).isEmpty();

            // ...and an arbitrary pattern with a predefined scheme binds cleanly too (the reported bug).
            assertThat(validator.validate(new TextSimilarityRequest("abc", "abd", null,
                    TokenizerScheme.LETTER, "anything"))).isEmpty();
        }
    }

    @Test
    @DisplayName("similarity - the retired 1..512 bound survives in the service for regex only (DOD-05)")
    void shouldBoundRegexPatternLengthInService() {
        assertThatThrownBy(() -> similarity("abc", "abd", null, TokenizerScheme.REGEX, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern is required when tokenizer is 'regex'");

        assertThatThrownBy(() -> similarity("abc", "abd", null, TokenizerScheme.REGEX, "a".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern must be 1..512 characters");

        assertThat(similarity("abc", "abd", null, TokenizerScheme.REGEX, "a".repeat(512))).isNotNull();

        TextSimilarityResponse withoutPattern = similarity("abc123", "abc456", null, TokenizerScheme.LETTER, null);
        TextSimilarityResponse overLongPattern =
                similarity("abc123", "abc456", null, TokenizerScheme.LETTER, "a".repeat(513));

        assertThat(overLongPattern.distance()).isEqualTo(withoutPattern.distance());
        assertThat(overLongPattern.maxLength()).isEqualTo(withoutPattern.maxLength());
        assertThat(overLongPattern.similarity()).isEqualTo(withoutPattern.similarity());
        assertThat(overLongPattern.identical()).isEqualTo(withoutPattern.identical());
    }

    @Test
    @DisplayName("similarity - a catastrophic custom pattern aborts on the regex budget (DOD-13)")
    void shouldBoundCustomTokenizerRegex() {
        TextService budgeted = new TextService(200, new SteppingClock(0L, 1000L));
        String backtracking = "a".repeat(1999) + "!";

        assertThatThrownBy(() -> budgeted.similarity(new TextSimilarityRequest(backtracking, "abc", null,
                TokenizerScheme.REGEX, "(a+)+$")))
                .isInstanceOf(RegexTimeoutException.class)
                .hasMessageContaining("regex evaluation timed out after")
                .hasMessageContaining("200");
    }
}
