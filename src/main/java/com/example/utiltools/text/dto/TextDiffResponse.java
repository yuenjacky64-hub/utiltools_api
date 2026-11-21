package com.example.utiltools.text.dto;

import java.util.List;
import java.util.Map;

/**
 * Response payload of {@code POST /api/v1/utils/text/diff}, wrapped in the standard
 * {@code ApiResponse} envelope.
 *
 * <p>Deliberate, documented divergence from {@code JsonDiffResponse}: its {@code added} and
 * {@code removed} are bare path strings, while the text diff carries a full {@link TextDiffDetail} for
 * every change so a consumer sees the token and its code-point span; {@code changed} is keyed by
 * {@code path}, exactly as in the JSON diff.</p>
 *
 * <p>Every collection is insertion-ordered — {@code added} and {@code removed} follow the discovery
 * order of their side, and {@code changed} is an unmodifiable view of an insertion-ordered map — so a
 * journalled result replays byte-for-byte and replay reports {@code VERIFIED}.</p>
 *
 * @param added         tokens that exist only in {@code right}, in {@code right} discovery order
 * @param removed       tokens that exist only in {@code left}, in {@code left} discovery order
 * @param changed       {@code path} to change, in the order the changes were discovered
 * @param summary       {@code "<a> added, <r> removed, <c> changed"}, or {@code "No differences found"}
 *                      when nothing changed
 * @param identical     {@code true} iff {@code added}, {@code removed} and {@code changed} are all empty
 * @param caseSensitive the folding rule that was applied; {@code false} when the request omitted it
 */
public record TextDiffResponse(
        List<TextDiffDetail> added,
        List<TextDiffDetail> removed,
        Map<String, TextDiffDetail> changed,
        String summary,
        boolean identical,
        boolean caseSensitive
) {
}
