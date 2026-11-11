package com.example.utiltools.text.dto;

/**
 * Response payload for a text truncation.
 *
 * @param result         the bounded text: the unchanged input when it fits, otherwise the
 *                      truncated prefix plus the ellipsis, never exceeding {@code maxLength}
 *                      code points and never splitting a surrogate pair
 * @param truncated      {@code true} when the result is shorter than the supplied text
 * @param originalLength the code-point count of the supplied text
 * @param length         the code-point count of {@code result}
 */
public record TextTruncateResponse(
        String result,
        boolean truncated,
        int originalLength,
        int length
) {}
