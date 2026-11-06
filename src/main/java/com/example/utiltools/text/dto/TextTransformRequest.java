package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/utils/text/transform}.
 *
 * @param text      the text to transform; must be non-blank
 * @param operation one of {@code UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG} (case-insensitive)
 */
public record TextTransformRequest(
        @NotBlank(message = "text is required")
        String text,

        @NotBlank(message = "operation is required")
        String operation
) {}
