package com.example.utiltools.text.dto;

/**
 * Response payload for a text find-and-replace.
 *
 * @param result       the text after replacement
 * @param replacements the number of matches actually replaced
 * @param changed      {@code true} when {@code result} differs from the supplied text
 * @param length       the code-point count of {@code result}
 */
public record TextReplaceResponse(
        String result,
        int replacements,
        boolean changed,
        int length
) {}
