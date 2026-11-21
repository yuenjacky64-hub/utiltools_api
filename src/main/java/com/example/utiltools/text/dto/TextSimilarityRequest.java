package com.example.utiltools.text.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/utils/text/similarity}.
 *
 * <p>The 2000-code-point bound on {@code left} and {@code right} is deliberately <em>not</em> a bean
 * validation constraint: {@code @Valid} failures are raised before the controller body runs, so an
 * over-long input would return {@code 400 VALIDATION_ERROR} without leaving a journal entry. The
 * bound is enforced by {@code TextService.similarity} instead, so an over-long input returns
 * {@code 400 BAD_REQUEST} and is journalled as a {@code FAILED} {@code TEXT_SIMILARITY} entry. The
 * same asymmetry applies to {@code ignoreWords}: its entry caps are bean validation, so an over-cap
 * list answers {@code 400 VALIDATION_ERROR} and is <em>not</em> journalled.</p>
 *
 * @param left          the first text to compare; must be non-blank and at most 2000 code points
 * @param right         the second text to compare; must be non-blank and at most 2000 code points
 * @param caseSensitive optional; when {@code false} or absent, both inputs are folded with
 *                      {@link java.util.Locale#ROOT} lowercasing before the distance is measured, so
 *                      {@code "Hello"} and {@code "hello"} compare identical
 * @param ignoreWords   optional words excluded from <em>both</em> inputs before the distance is
 *                      measured; at most 1000 non-blank entries of at most 100 characters each;
 *                      matched with the same folding as the inputs, so with the case-insensitive
 *                      default ignoring {@code "Foo"} also drops {@code foo} and {@code FOO}, while
 *                      {@code caseSensitive=true} makes matching an exact string comparison
 */
public record TextSimilarityRequest(
        @NotBlank(message = "left is required")
        String left,

        @NotBlank(message = "right is required")
        String right,

        Boolean caseSensitive,

        @Valid
        @Size(max = 1000, message = "ignoreWords must contain at most 1000 entries")
        List<@NotBlank(message = "ignoreWords entries must not be blank")
             @Size(max = 100, message = "each ignoreWords entry must be at most 100 characters")
             String> ignoreWords
) {

    /** Three-argument form kept for callers written before {@code ignoreWords} existed. */
    public TextSimilarityRequest(String left, String right, Boolean caseSensitive) {
        this(left, right, caseSensitive, null);
    }
}
