package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/utils/text/transform}.
 *
 * @param text      the text to transform; must be non-blank and at most 1000000 characters
 * @param operation one of {@code UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE};
 *                  matched case-insensitively, surrounding whitespace is not accepted
 */
public record TextTransformRequest(
        @NotBlank(message = "text is required")
        @Size(max = 1000000, message = "text must be at most 1000000 characters")
        String text,

        @NotBlank(message = "operation is required")
        @Pattern(regexp = "(?i)\\b(UPPER|LOWER|TITLE|CAMEL|SNAKE|KEBAB|SLUG|SENTENCE_CASE)\\b",
                message = "Supported operations: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE")
        String operation
) {}
