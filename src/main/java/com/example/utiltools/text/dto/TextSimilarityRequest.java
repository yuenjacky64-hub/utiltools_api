package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;

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
 */
public record TextSimilarityRequest(
        @NotBlank(message = "left is required")
        String left,

        @NotBlank(message = "right is required")
        String right,

        Boolean caseSensitive
) {}
