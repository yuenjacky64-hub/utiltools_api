package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/utils/text/similarity}.
 *
 * <p>The 2000-code-point bound on {@code left} and {@code right} is deliberately <em>not</em> a bean
 * validation constraint: {@code @Valid} failures are raised before the controller body runs, so an
 * over-long input would return {@code 400 VALIDATION_ERROR} without leaving a journal entry. The
 * bound is enforced by {@code TextService.similarity} instead, so an over-long input returns
 * {@code 400 BAD_REQUEST} and is journalled as a {@code FAILED} {@code TEXT_SIMILARITY} entry.</p>
 *
 * @param left          the first text to compare; must be non-blank and at most 2000 code points
 * @param right         the second text to compare; must be non-blank and at most 2000 code points
 * @param caseSensitive optional; when {@code false} or absent, both inputs are folded with
 *                      {@link java.util.Locale#ROOT} lowercasing before the distance is measured, so
 *                      {@code "Hello"} and {@code "hello"} compare identical
 * @param tokenizer     optional token scheme applied to both inputs before the code-point distance is
 *                      measured: {@code letter} (the default, the folded input verbatim, i.e. exactly
 *                      today's behaviour), {@code digit} (digit runs), {@code alphanum} (alphanumeric
 *                      runs split where letters meet digits), {@code whitespace} (non-whitespace runs) or
 *                      {@code regex}. Matching is case-insensitive on the wire; {@code null} means
 *                      {@code letter}. The four run-based schemes <em>select</em> tokens — everything
 *                      else is discarded — and their consecutive tokens are compared joined by one
 *                      space
 * @param pattern       optional; the regular expression whose non-empty matches are the tokens when
 *                      {@code tokenizer} is {@code regex}. Required for that scheme (a missing or
 *                      blank value is rejected as {@code 400 BAD_REQUEST}), bounded to 1..512
 *                      characters, and ignored for every predefined scheme
 */
public record TextSimilarityRequest(
        @NotBlank(message = "left is required")
        String left,

        @NotBlank(message = "right is required")
        String right,

        Boolean caseSensitive,

        TokenizerScheme tokenizer,

        @Pattern(regexp = "^[\\s\\S]{1,512}$", message = "pattern must be 1..512 characters")
        String pattern
) {}
