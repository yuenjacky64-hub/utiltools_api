package com.example.utiltools.text.dto;

/**
 * One reported change of {@code POST /api/v1/utils/text/diff}: a token that was added, removed or
 * changed between the request's {@code left} and {@code right} text.
 *
 * <p>The {@code path} is the structural address of the change, {@code line[L].token[T]} with both
 * indexes zero-based: {@code L} counts {@code U+000A} separators in the raw input and {@code T} counts
 * the tokens the selected {@code tokenizer} found earlier on that line. An {@code ADDED} path and its
 * code-point span index {@code right}, a {@code REMOVED} path and span index {@code left}, and a
 * {@code CHANGED} path indexes {@code left} while carrying a span for each side. Positions are
 * half-open code-point offsets into the request's own raw string, so a client can slice the original
 * text with them unchanged (a supplementary character counts as one code point, never two).</p>
 *
 * <p>The span of the side a change does not touch is {@code -1}-{@code -1}, and so is the
 * corresponding token text: an {@code ADDED} detail has a {@code null} {@code oldToken}, and a
 * {@code REMOVED} detail has a {@code null} {@code newToken}.</p>
 *
 * @param path              structural address of the change, {@code line[L].token[T]}
 * @param changeType        {@link #ADDED}, {@link #REMOVED} or {@link #CHANGED}
 * @param oldToken          the token as it appears in {@code left}; {@code null} for {@code ADDED}
 * @param newToken          the token as it appears in {@code right}; {@code null} for {@code REMOVED}
 * @param oldStartCodePoint inclusive start of the token in {@code left}; {@code -1} for {@code ADDED}
 * @param oldEndCodePoint   exclusive end of the token in {@code left}; {@code -1} for {@code ADDED}
 * @param newStartCodePoint inclusive start of the token in {@code right}; {@code -1} for {@code REMOVED}
 * @param newEndCodePoint   exclusive end of the token in {@code right}; {@code -1} for {@code REMOVED}
 */
public record TextDiffDetail(
        String path,
        String changeType,
        String oldToken,
        String newToken,
        int oldStartCodePoint,
        int oldEndCodePoint,
        int newStartCodePoint,
        int newEndCodePoint
) {

    /** The token exists only in the request's {@code right} text. */
    public static final String ADDED = "ADDED";

    /** The token exists only in the request's {@code left} text. */
    public static final String REMOVED = "REMOVED";

    /** The token exists on both sides with different text; one detail carries both tokens and spans. */
    public static final String CHANGED = "CHANGED";
}
