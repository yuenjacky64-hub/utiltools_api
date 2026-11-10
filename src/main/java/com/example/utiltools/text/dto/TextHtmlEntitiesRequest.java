package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/utils/text/html-entities}.
 *
 * @param action {@code ENCODE} escapes the five HTML-significant characters, {@code DECODE} resolves
 *               named and numeric character references; matched case-insensitively
 * @param text   the text to encode or decode; must be non-blank
 */
public record TextHtmlEntitiesRequest(
        @NotBlank(message = "action is required")
        @Pattern(regexp = "(?i)^(ENCODE|DECODE)$", message = "action must be either ENCODE or DECODE")
        String action,

        @NotBlank(message = "text is required")
        String text
) {}
