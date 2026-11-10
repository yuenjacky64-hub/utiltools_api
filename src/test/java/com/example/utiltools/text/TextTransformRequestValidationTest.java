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
            "Supported operations: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE";

    private static final String UPPER = "UPPER";
    private static final String LOWER = "LOWER";
    private static final String TITLE = "TITLE";
    private static final String CAMEL = "CAMEL";
    private static final String SNAKE = "SNAKE";
    private static final String KEBAB = "KEBAB";
    private static final String SLUG = "SLUG";
    private static final String SENTENCE_CASE = "SENTENCE_CASE";

    /** Mirrors {@code TextService}'s {@code switch} labels: an accepted value must land on one of them. */
    private static final List<String> SWITCH_LABELS =
            List.of(UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE);

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
    @DisplayName("all eight operations pass validation in lower, mixed and upper case and survive the service normalisation")
    void shouldAcceptAllEightOperationsInAnyCase() {
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

    private static String mixedCase(String label) {
        StringBuilder mixed = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            mixed.append(i % 2 == 0 ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return mixed.toString();
    }
}
