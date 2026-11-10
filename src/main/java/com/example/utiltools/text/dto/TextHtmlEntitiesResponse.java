package com.example.utiltools.text.dto;

/**
 * Response payload for an HTML-entity encode or decode.
 *
 * @param action  the action echoed from the request, verbatim
 * @param result  the text after encoding or decoding
 * @param changed {@code true} when {@code result} differs from the supplied text
 * @param length  the code-point count of {@code result}
 */
public record TextHtmlEntitiesResponse(
        String action,
        String result,
        boolean changed,
        int length
) {}
