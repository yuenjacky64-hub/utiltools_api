package com.example.utiltools.text.dto;

/**
 * Response payload for a text normalization.
 *
 * @param form    the requested form, echoed upper-cased
 * @param result  the normalized text
 * @param changed {@code true} when {@code result} differs from the supplied text
 * @param length  the code-point count of {@code result}
 */
public record TextNormalizeResponse(
        String form,
        String result,
        boolean changed,
        int length
) {}
