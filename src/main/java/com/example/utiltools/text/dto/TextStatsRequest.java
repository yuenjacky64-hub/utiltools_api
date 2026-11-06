package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/utils/text/stats}.
 *
 * @param text the text to analyse; must be non-blank
 */
public record TextStatsRequest(
        @NotBlank(message = "text is required")
        String text
) {}
