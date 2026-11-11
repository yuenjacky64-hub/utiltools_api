package com.example.utiltools.text.dto;

import java.util.List;

/**
 * Response body for {@code POST /api/v1/utils/text/word-frequency}.
 *
 * <p>{@code totalWords} and {@code distinctWords} count only analysed (non-ignored) tokens: words
 * matched by {@code ignoreWords} are excluded before counting. {@code topWords} holds at most
 * {@code limit} entries (default 10 when absent) ordered by {@code count} descending, then
 * {@code word} ascending, so a journalled result always replays exactly.</p>
 *
 * @param totalWords    number of analysed (non-ignored) tokens in {@code text}
 * @param distinctWords number of distinct analysed (non-ignored) tokens in {@code text}
 * @param topWords      at most {@code limit} entries, ordered by count descending, then word ascending
 */
public record TextWordFrequencyResponse(
        int totalWords,
        int distinctWords,
        List<WordCount> topWords
) {
    /** One top-word entry. */
    public record WordCount(String word, int count) {}
}
