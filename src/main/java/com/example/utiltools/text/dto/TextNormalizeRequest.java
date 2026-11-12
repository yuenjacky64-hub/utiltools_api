package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/utils/text/normalize}.
 *
 * @param text the text to normalise; must be non-blank and at most 1000000 characters
 * @param form one of {@code NFC, NFD, NFKC, NFKD}; matched case-insensitively,
 *             surrounding whitespace is not accepted
 */
public record TextNormalizeRequest(
        @NotBlank(message = "text is required")
        @Size(max = 1000000, message = "text must be at most 1000000 characters")
        String text,

        @NotBlank(message = "form is required")
        @Pattern(regexp = "(?i)\\b(NFC|NFD|NFKC|NFKD)\\b",
                message = "Supported forms: NFC, NFD, NFKC, NFKD")
        String form
) {}
