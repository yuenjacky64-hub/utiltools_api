package com.example.utiltools.text.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/utils/text/word-frequency}.
 *
 * @param text          the text to analyse; must be non-blank and at most 1000000 characters
 * @param limit         optional maximum number of {@code topWords} entries, 1..100; defaults to 10 when absent
 * @param caseSensitive optional; when {@code false} or absent, tokens and ignore words are folded with
 *                      {@code Locale.ROOT} lowercasing before counting and matching
 * @param ignoreWords   optional words excluded from the analysis; at most 1000 non-blank entries of at
 *                      most 100 characters each; matched with the same folding as the tokens, so with
 *                      the case-insensitive default ignoring {@code "Foo"} also drops {@code foo} and {@code FOO}
 */
public record TextWordFrequencyRequest(
        @NotBlank(message = "text is required")
        @Size(max = 1000000, message = "text must be at most 1000000 characters")
        String text,

        @Min(value = 1, message = "limit must be at least 1")
        @Max(value = 100, message = "limit must be at most 100")
        Integer limit,

        Boolean caseSensitive,

        @Valid
        @Size(max = 1000, message = "ignoreWords must contain at most 1000 entries")
        List<@NotBlank(message = "ignoreWords entries must not be blank")
             @Size(max = 100, message = "each ignoreWords entry must be at most 100 characters")
             String> ignoreWords
) {}
