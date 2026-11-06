package com.example.utiltools.text.dto;

/**
 * Response payload for a text transformation.
 *
 * @param operation the applied operation, normalised to upper case
 * @param result    the transformed text
 * @param changed   {@code true} when {@code result} differs from the supplied text
 * @param length    the code-point count of {@code result}
 */
public record TextTransformResponse(
        String operation,
        String result,
        boolean changed,
        int length
) {}
