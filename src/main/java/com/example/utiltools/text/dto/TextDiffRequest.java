package com.example.utiltools.text.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/utils/text/diff}.
 *
 * <p>The route reports the added, removed and changed tokens between {@code left} and {@code right};
 * it is the per-change companion of {@code POST /api/v1/utils/text/similarity}, which answers with a
 * single edit distance.</p>
 *
 * <p>{@code left} and {@code right} carry {@link NotBlank} and {@link Size} so a blank side answers
 * {@code 400 VALIDATION_ERROR} with the {@code "left is required"} / {@code "right is required"}
 * message and a side longer than 2000 characters answers {@code 400 VALIDATION_ERROR} with
 * {@code "left must be at most 2000 characters"} / {@code "right must be at most 2000 characters"}.
 * The annotation is {@link Size} rather than {@link Max} because Hibernate Validator's {@code @Max}
 * support for a {@link CharSequence} validates the string as a <em>number</em>, not as a length, so it
 * would reject {@code "abc"} outright. {@code @Size} bounds the UTF-16 length, and the authoritative
 * code-point bound stays in {@code TextService#diff}, which rejects a side over 2000 code points with
 * {@code 400 BAD_REQUEST} and journals the attempt as {@code FAILED}.</p>
 *
 * @param left          the original text; must be non-blank and at most 2000 code points
 * @param right         the revised text; must be non-blank and at most 2000 code points
 * @param caseSensitive optional; when {@code false} or absent, every token is folded with
 *                      {@link java.util.Locale#ROOT} lowercasing before tokens are compared, so
 *                      {@code "Hello"} and {@code "hello"} are the same token
 * @param tokenizer     optional token scheme applied to both inputs: {@code letter} (the default, the
 *                      project's letter/digit run tokenizer), {@code digit} (digit runs),
 *                      {@code alphanum} (alphanumeric runs split where letters meet digits),
 *                      {@code whitespace} (non-whitespace runs) or {@code regex}. Matching is
 *                      case-insensitive on the wire; {@code null} means {@code letter}
 * @param pattern       optional; the regular expression whose non-empty matches are the tokens when
 *                      {@code tokenizer} is {@code regex}. At most 512 characters — a longer value is
 *                      a {@code 400 VALIDATION_ERROR} for every scheme. Required for the {@code regex}
 *                      scheme — a missing, blank or non-compiling value is rejected as
 *                      {@code 400 BAD_REQUEST}; ignored for every predefined scheme
 * @param timeoutMs     optional; the wall-clock budget in milliseconds for a {@code regex} token
 *                      evaluation, {@code 0}..{@code 60000}. Absent, {@code null} and {@code 0} all
 *                      mean no per-request override, i.e. the configured
 *                      {@code utiltools.text.regex-timeout-ms} budget applies. A positive value may
 *                      only <em>tighten</em> that budget; an exhausted budget answers
 *                      {@code 422 REGEX_TIMEOUT}. Ignored for every predefined scheme
 */
public record TextDiffRequest(
        @NotBlank(message = "left is required")
        @Size(max = 2000, message = "left must be at most 2000 characters")
        String left,

        @NotBlank(message = "right is required")
        @Size(max = 2000, message = "right must be at most 2000 characters")
        String right,

        Boolean caseSensitive,

        TokenizerScheme tokenizer,

        @Size(max = 512, message = "pattern must be at most 512 characters")
        String pattern,

        @Min(value = 0, message = "timeoutMs must be at least 0")
        @Max(value = 60000, message = "timeoutMs must be at most 60000")
        Integer timeoutMs
) {

    /** Two-argument form: case-insensitive {@code letter} tokenization, no pattern and no timeout. */
    public TextDiffRequest(String left, String right) {
        this(left, right, null, null, null, null);
    }

    /** Three-argument form kept for callers that only pin the folding rule. */
    public TextDiffRequest(String left, String right, Boolean caseSensitive) {
        this(left, right, caseSensitive, null, null, null);
    }
}
