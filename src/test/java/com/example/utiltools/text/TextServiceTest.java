package com.example.utiltools.text;

import com.example.utiltools.text.dto.TextReplaceRequest;
import com.example.utiltools.text.dto.TextReplaceResponse;
import com.example.utiltools.text.dto.TextStatsRequest;
import com.example.utiltools.text.dto.TextStatsResponse;
import com.example.utiltools.text.dto.TextTransformRequest;
import com.example.utiltools.text.dto.TextTransformResponse;
import com.example.utiltools.text.service.TextService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextServiceTest {

    private final TextService service = new TextService();

    @Test
    @DisplayName("every allowed operation maps its documented input to its documented output")
    void shouldTransformEveryOperation() {
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "UPPER")))
                .isEqualTo(new TextTransformResponse("UPPER", "HELLO, WORLD!", true, 13));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "LOWER")))
                .isEqualTo(new TextTransformResponse("LOWER", "hello, world!", true, 13));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "TITLE")))
                .isEqualTo(new TextTransformResponse("TITLE", "Hello World", true, 11));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "CAMEL")))
                .isEqualTo(new TextTransformResponse("CAMEL", "helloWorld", true, 10));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "SNAKE")))
                .isEqualTo(new TextTransformResponse("SNAKE", "hello_world", true, 11));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "KEBAB")))
                .isEqualTo(new TextTransformResponse("KEBAB", "hello-world", true, 11));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "SLUG")))
                .isEqualTo(new TextTransformResponse("SLUG", "hello-world", true, 11));

        // case-insensitive operation, echoed back upper-case
        assertThat(service.transform(new TextTransformRequest("Hello, World!", " snake ")))
                .isEqualTo(new TextTransformResponse("SNAKE", "hello_world", true, 11));

        // TITLE case-titles every token, so interior capitalisation is normalised
        assertThat(service.transform(new TextTransformRequest("hello wORLD", "TITLE")).result())
                .isEqualTo("Hello World");

        // UPPER/LOWER keep punctuation and spacing exactly as supplied
        assertThat(service.transform(new TextTransformRequest("  a  b  ", "UPPER")).result())
                .isEqualTo("  A  B  ");
    }

    @Test
    @DisplayName("SLUG strips diacritics via NFD and joins with a single hyphen")
    void shouldSlugDiacritics() {
        TextTransformResponse response = service.transform(new TextTransformRequest("Café déjà vu!", "SLUG"));

        assertThat(response.operation()).isEqualTo("SLUG");
        assertThat(response.result()).isEqualTo("cafe-deja-vu");
        assertThat(response.changed()).isTrue();
        assertThat(response.length()).isEqualTo(12);

        // combining marks never leave empty or doubled hyphens behind
        assertThat(service.transform(new TextTransformRequest("  Ünïcode — Tèst  ", "SLUG")).result())
                .isEqualTo("unicode-test");
    }

    @Test
    @DisplayName("stats count code points, including surrogate pairs, and split lines and sentences")
    void shouldCountMultilineEmojiStats() {
        TextStatsResponse response = service.stats(new TextStatsRequest("Line one.\nTwo 😀 lines! Bye?"));

        assertThat(response).isEqualTo(new TextStatsResponse(27, 22, 5, 2, 3));

        // an emoji is one code point (two UTF-16 chars) and zero words
        TextStatsResponse emoji = service.stats(new TextStatsRequest("😀"));
        assertThat(emoji.characters()).isEqualTo(1);
        assertThat(emoji.charactersNoWhitespace()).isEqualTo(1);
        assertThat(emoji.words()).isZero();
        assertThat(emoji.lines()).isEqualTo(1);
        assertThat(emoji.sentences()).isEqualTo(1);

        // CRLF and CR normalise to the same line count
        assertThat(service.stats(new TextStatsRequest("a\r\nb\rc")).lines()).isEqualTo(3);
    }

    @Test
    @DisplayName("blank text is rejected by the request contract")
    void shouldRejectBlankText() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<ConstraintViolation<TextTransformRequest>> transformViolations =
                    validator.validate(new TextTransformRequest("   ", "SNAKE"));
            assertThat(transformViolations).hasSize(1);
            assertThat(transformViolations).extracting(ConstraintViolation::getMessage)
                    .contains("text is required");

            Set<ConstraintViolation<TextTransformRequest>> operationViolations =
                    validator.validate(new TextTransformRequest("Hello", " "));
            assertThat(operationViolations).extracting(ConstraintViolation::getMessage)
                    .containsExactly("operation is required");

            Set<ConstraintViolation<TextStatsRequest>> statsViolations =
                    validator.validate(new TextStatsRequest(""));
            assertThat(statsViolations).extracting(ConstraintViolation::getMessage)
                    .containsExactly("text is required");
        }
    }

    @Test
    @DisplayName("an unknown operation fails with BAD_REQUEST and echoes only the operation value")
    void shouldRejectUnknownOperation() {
        assertThatThrownBy(() -> service.transform(new TextTransformRequest("secret body", "shout")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported operation 'shout'. Allowed values: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG")
                .hasMessageNotContaining("secret body");
    }

    @Test
    @DisplayName("changed and length are computed against the result in code points")
    void shouldReportChangedAndLengthInCodePoints() {
        TextTransformResponse upper = service.transform(new TextTransformRequest("HELLO", "UPPER"));
        assertThat(upper.result()).isEqualTo("HELLO");
        assertThat(upper.changed()).isFalse();
        assertThat(upper.length()).isEqualTo(5);

        TextTransformResponse slug = service.transform(new TextTransformRequest("Café", "SLUG"));
        assertThat(slug.result()).isEqualTo("cafe");
        assertThat(slug.length()).isEqualTo(4);

        TextTransformResponse emoji = service.transform(new TextTransformRequest("😀 Hello", "SNAKE"));
        assertThat(emoji.result()).isEqualTo("hello");
        assertThat(emoji.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("regex mode expands $n group references for every match")
    void shouldReplaceRegexWithGroupReferences() {
        assertThat(service.replace(new TextReplaceRequest("a1 b2", "(\\w)(\\d)", "$2$1", null, null, null)))
                .isEqualTo(new TextReplaceResponse("1a 2b", 2, true, 5));
    }

    @Test
    @DisplayName("literal mode replaces regex metacharacters verbatim")
    void shouldReplaceLiteralMetacharacters() {
        assertThat(service.replace(new TextReplaceRequest("a.b", ".", "X", null, true, null)))
                .isEqualTo(new TextReplaceResponse("aXb", 1, true, 3));
    }

    @Test
    @DisplayName("all=false replaces only the first match")
    void shouldReplaceFirstMatchOnlyWhenAllFalse() {
        assertThat(service.replace(new TextReplaceRequest("aaa", "a", "b", false, null, null)))
                .isEqualTo(new TextReplaceResponse("baa", 1, true, 3));
    }

    @Test
    @DisplayName("caseInsensitive=true matches regardless of case")
    void shouldReplaceCaseInsensitively() {
        assertThat(service.replace(new TextReplaceRequest("Hello HELLO", "hello", "bye", null, null, true)))
                .isEqualTo(new TextReplaceResponse("bye bye", 2, true, 7));
    }

    @Test
    @DisplayName("a no-match request leaves the text unchanged")
    void shouldReportNoMatchUnchanged() {
        assertThat(service.replace(new TextReplaceRequest("xyz", "q", "b", null, null, null)))
                .isEqualTo(new TextReplaceResponse("xyz", 0, false, 3));
    }

    @Test
    @DisplayName("an invalid regex fails with a BAD_REQUEST-shaped message")
    void shouldRejectInvalidRegex() {
        assertThatThrownBy(() -> service.replace(new TextReplaceRequest("abc", "(", "x", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid regex pattern '('");
    }

    @Test
    @DisplayName("length measures code points of the result")
    void shouldMeasureCodePointLengthAfterReplace() {
        assertThat(service.replace(new TextReplaceRequest("\uD83D\uDE00a\uD83D\uDE00", "\uD83D\uDE00", "", null, null, null)))
                .isEqualTo(new TextReplaceResponse("a", 2, true, 1));
    }

    @Test
    @DisplayName("TextReplaceRequest constraint violations fire on every bound")
    void shouldValidateReplaceRequest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new TextReplaceRequest("   ", "a", "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .contains("text is required");

            assertThat(validator.validate(new TextReplaceRequest("abc", " ", "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("pattern is required");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a", null, null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("replacement is required");

            assertThat(validator.validate(new TextReplaceRequest("a".repeat(100001), "a", "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("text must be at most 100000 characters");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a".repeat(513), "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("pattern must be at most 512 characters");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a", "b".repeat(100001), null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("replacement must be at most 100000 characters");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a", "b", null, null, null))).isEmpty();
        }
    }

    @Test
    @DisplayName("an oversized replacement result is rejected before it is materialised")
    void shouldRejectOversizedReplacementResult() {
        assertThatThrownBy(() -> service.replace(
                new TextReplaceRequest("a".repeat(2000), "a", "b".repeat(2000), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("replacement result exceeds 1000000 characters");
    }

    @Test
    @DisplayName("a group reference that would expand past the bound is rejected without expanding")
    void shouldRejectOversizedGroupReferenceExpansion() {
        String text = "a".repeat(100000);
        String pattern = "(a{100000})";
        String replacement = "$1".repeat(50000);

        assertThatThrownBy(() -> service.replace(new TextReplaceRequest(text, pattern, replacement, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("replacement result exceeds 1000000 characters");
    }

    @Test
    @DisplayName("all=false is bounded by the first match, not by every match")
    void shouldAcceptFirstMatchOnlyWithLargeReplacement() {
        TextReplaceResponse response = service.replace(
                new TextReplaceRequest("a".repeat(100000), "a", "b".repeat(11), false, null, null));

        assertThat(response.replacements()).isEqualTo(1);
        assertThat(response.changed()).isTrue();
        assertThat(response.result()).startsWith("b".repeat(11) + "a");
        assertThat(response.length()).isEqualTo(100010);
    }
}
