package com.example.utiltools.text;

import com.example.utiltools.text.dto.TextTransformRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code @Pattern} constraint on {@link TextTransformRequest#operation()} in isolation, without a
 * Spring context, and proves that it agrees with the case normalisation performed by
 * {@code TextService.transform} ({@code trim().toUpperCase(Locale.ROOT)}).
 */
class TextTransformRequestValidationTest {

    private static final String SUPPORTED_OPERATIONS_MESSAGE =
            "Supported operations: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE, REVERSE";

    private static final String TEXT_TOO_LONG_MESSAGE = "text must be at most 1000000 characters";

    private static final String UPPER = "UPPER";
    private static final String LOWER = "LOWER";
    private static final String TITLE = "TITLE";
    private static final String CAMEL = "CAMEL";
    private static final String SNAKE = "SNAKE";
    private static final String KEBAB = "KEBAB";
    private static final String SLUG = "SLUG";
    private static final String SENTENCE_CASE = "SENTENCE_CASE";
    private static final String REVERSE = "REVERSE";

    /** Mirrors {@code TextService}'s {@code switch} labels: an accepted value must land on one of them. */
    private static final List<String> SWITCH_LABELS =
            List.of(UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE, REVERSE);

    @Test
    @DisplayName("text of exactly 1000000 characters passes @Size, 1000001 is rejected, null stays @NotBlank-only")
    void shouldRejectTextLongerThanOneMillionCharacters() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<ConstraintViolation<TextTransformRequest>> tooLong =
                    validator.validate(new TextTransformRequest("a".repeat(1_000_001), "UPPER"));

            assertThat(tooLong).hasSize(1);
            assertThat(tooLong).extracting(ConstraintViolation::getMessage)
                    .containsExactly(TEXT_TOO_LONG_MESSAGE);
            assertThat(tooLong).extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("text");

            assertThat(validator.validate(new TextTransformRequest("a".repeat(1_000_000), "UPPER")))
                    .as("@Size(max = 1000000) must accept a text of exactly 1000000 characters")
                    .isEmpty();

            assertThat(validator.validate(new TextTransformRequest(null, "UPPER")))
                    .as("@Size ignores null, so @NotBlank stays the sole message")
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("text is required");
        }
    }

    @Test
    @DisplayName("an unsupported operation violates @Pattern with the exact supported-operations message")
    void shouldRejectUnsupportedOperationWithExactMessage() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<ConstraintViolation<TextTransformRequest>> violations =
                    validator.validate(new TextTransformRequest("hello", "FOO"));

            assertThat(violations).hasSize(1);
            assertThat(violations).extracting(ConstraintViolation::getMessage)
                    .containsExactly(SUPPORTED_OPERATIONS_MESSAGE);
            assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("operation");
        }
    }

    @Test
    @DisplayName("all nine operations pass validation in lower, mixed and upper case and survive the service normalisation")
    void shouldAcceptAllNineOperationsInAnyCase() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            for (String label : SWITCH_LABELS) {
                for (String candidate : List.of(label.toLowerCase(Locale.ROOT),
                        mixedCase(label),
                        label)) {
                    Set<ConstraintViolation<TextTransformRequest>> violations =
                            validator.validate(new TextTransformRequest("hello", candidate));

                    assertThat(violations)
                            .as("validation must accept operation '%s'", candidate)
                            .isEmpty();
                    assertThat(SWITCH_LABELS)
                            .as("operation '%s' must survive trim().toUpperCase(Locale.ROOT)", candidate)
                            .contains(candidate.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    @Test
    @DisplayName("values the service switch cannot handle are rejected: padding, prefixes, null-ish and empty")
    void shouldRejectValuesTheServiceSwitchCannotHandle() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            for (String candidate : List.of(" sentence_case ", "UPPER ", " SLUG", "SLUGGISH", "UPPER_CASE",
                    "FOLD", "TITLEX", "")) {
                Set<ConstraintViolation<TextTransformRequest>> violations =
                        validator.validate(new TextTransformRequest("hello", candidate));

                assertThat(violations)
                        .as("validation must reject operation '%s'", candidate)
                        .extracting(ConstraintViolation::getMessage)
                        .contains(SUPPORTED_OPERATIONS_MESSAGE);
            }

            assertThat(validator.validate(new TextTransformRequest("hello", null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("operation is required");
        }
    }

    @Test
    @DisplayName("an unrecognized value with the REVERSE prefix is rejected with the updated supported-operations message")
    void shouldRejectReversePrefixValue() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<TextTransformRequest>> violations =
                    validator.validate(new TextTransformRequest("hello", "REVERSED"));

            assertThat(violations)
                    .hasSize(1)
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly(SUPPORTED_OPERATIONS_MESSAGE);
        }
    }

    private static String mixedCase(String label) {
        StringBuilder mixed = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            mixed.append(i % 2 == 0 ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return mixed.toString();
    }
}
