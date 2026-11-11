package com.example.utiltools.text.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/utils/text/truncate}.
 *
 * @param text         the text to bound; must be non-blank and at most 1000000 code points
 * @param maxLength    the maximum code-point count of the result, ellipsis included; required, 1..1000000
 * @param ellipsis     optional marker appended when truncation happens; {@code null} defaults to "…"
 *                     and an empty value means no marker; at most 16 code points
 * @param wordBoundary optional; {@code true} cuts at the last whitespace before the limit,
 *                     {@code false} (the default when absent) hard-cuts
 */
public record TextTruncateRequest(
        @NotBlank(message = "text is required")
        @Size(max = 1000000, message = "text must be at most 1000000 code points")
        String text,

        @NotNull(message = "maxLength is required")
        @Min(value = 1, message = "maxLength must be at least 1")
        @Max(value = 1000000, message = "maxLength must be at most 1000000")
        Integer maxLength,

        @Size(max = 16, message = "ellipsis must be at most 16 code points")
        String ellipsis,

        Boolean wordBoundary
) {}
