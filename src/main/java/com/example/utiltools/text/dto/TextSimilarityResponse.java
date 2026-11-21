package com.example.utiltools.text.dto;

/**
 * Response body for {@code POST /api/v1/utils/text/similarity}.
 *
 * <p>{@code distance} is the Levenshtein distance measured in Unicode code points between the two
 * inputs as compared (i.e. after the {@link java.util.Locale#ROOT} case folding applied when
 * {@code caseSensitive} is {@code false}). {@code maxLength} is the longer of the two compared
 * code-point lengths; {@code similarity} is {@code 1 - distance / maxLength} rounded to six
 * decimals, so a journalled result always replays exactly.</p>
 *
 * <p>{@code identical} is {@code distance == 0}, i.e. equality <em>under the requested case rule</em>:
 * with the default {@code caseSensitive=false}, {@code "Hello"} and {@code "hello"} are identical.
 * {@code caseSensitive} echoes the rule that was applied, defaulting to {@code false}.</p>
 *
 * @param distance      code-point Levenshtein distance between the compared inputs
 * @param maxLength     the longer of the two compared code-point lengths
 * @param similarity    {@code 1 - distance / maxLength} rounded to six decimals, always within {@code [0, 1]}
 * @param identical     {@code true} when {@code distance == 0}, i.e. equal under the requested case rule
 * @param caseSensitive the case rule that was applied; {@code false} when the request omitted it
 */
public record TextSimilarityResponse(
        int distance,
        int maxLength,
        double similarity,
        boolean identical,
        boolean caseSensitive
) {}
