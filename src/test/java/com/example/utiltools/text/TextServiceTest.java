package com.example.utiltools.text;

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
}
