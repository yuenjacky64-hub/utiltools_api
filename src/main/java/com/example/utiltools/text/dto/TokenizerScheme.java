package com.example.utiltools.text.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Tokenizer scheme applied to both inputs of {@code POST /api/v1/utils/text/similarity} before the
 * code-point Levenshtein distance is measured (REQ-01/REQ-02).
 *
 * <p>The wire names are the lowercase identifiers {@code letter}, {@code digit}, {@code alphanum},
 * {@code whitespace} and {@code regex}. {@link #fromWire(String)} trims and lowercases
 * ({@link Locale#ROOT}) the incoming text, so {@code "digit"}, {@code "DIGIT"} and {@code " digit "}
 * all bind to {@link #DIGIT}; an absent or blank value binds to {@link #LETTER}, which keeps every
 * pre-existing request body on today's behaviour. Any other value is rejected during JSON binding,
 * so the request is never journalled.</p>
 */
public enum TokenizerScheme {

    /**
     * The folded input verbatim, i.e. one token — exactly today's code-point behaviour, so punctuation,
     * whitespace, symbols and supplementary code points keep counting as single edits.
     */
    LETTER("letter"),

    /** Maximal runs of {@link Character#isDigit(int)} code points. */
    DIGIT("digit"),

    /** Maximal runs of {@link Character#isLetterOrDigit(int)} code points, additionally split where the run switches between letters and digits. */
    ALPHANUM("alphanum"),

    /** Maximal runs of non-whitespace code points. */
    WHITESPACE("whitespace"),

    /** The tokens selected by the caller's {@code pattern}; text outside the matches is discarded. */
    REGEX("regex");

    private final String wireName;

    TokenizerScheme(String wireName) {
        this.wireName = wireName;
    }

    /** The canonical lowercase name this scheme serialises to, and accepts, on the wire. */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * Case-insensitive, trimming wire-name lookup.
     *
     * @param value the incoming wire value; {@code null} or blank means {@link #LETTER}
     * @return the matching scheme
     * @throws IllegalArgumentException when the value is not one of the five wire names
     */
    @JsonCreator
    public static TokenizerScheme fromWire(String value) {
        if (value == null) {
            return LETTER;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return LETTER;
        }
        for (TokenizerScheme scheme : values()) {
            if (scheme.wireName.equals(normalized)) {
                return scheme;
            }
        }
        throw new IllegalArgumentException("Unsupported tokenizer '" + value
                + "'. Allowed values: letter, digit, alphanum, whitespace, regex");
    }
}
