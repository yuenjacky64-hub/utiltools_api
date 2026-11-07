package com.example.utiltools.text.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/utils/text/replace}.
 *
 * @param text            the text to search; must be non-blank and at most 100000 characters
 * @param pattern         the literal or regular-expression pattern; must be non-blank and at most 512 characters
 * @param replacement     the replacement string; must be present and at most 100000 characters
 * @param all             {@code true} (default when absent) replaces every match, {@code false} only the first
 * @param literal         {@code true} quotes the pattern and the replacement so both match/insert verbatim
 * @param caseInsensitive {@code true} compiles the pattern with {@link java.util.regex.Pattern#CASE_INSENSITIVE}
 */
public record TextReplaceRequest(
        @NotBlank(message = "text is required")
        @Size(max = 100000, message = "text must be at most 100000 characters")
        String text,

        @NotBlank(message = "pattern is required")
        @Size(max = 512, message = "pattern must be at most 512 characters")
        String pattern,

        @NotNull(message = "replacement is required")
        @Size(max = 100000, message = "replacement must be at most 100000 characters")
        String replacement,

        Boolean all,
        Boolean literal,
        Boolean caseInsensitive
) {}
