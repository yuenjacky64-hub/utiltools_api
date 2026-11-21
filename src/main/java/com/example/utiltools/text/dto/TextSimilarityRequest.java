package com.example.utiltools.text.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/utils/text/similarity}.
 *
 * <p>The 2000-code-point bound on {@code left} and {@code right} is deliberately <em>not</em> a bean
 * validation constraint: {@code @Valid} failures are raised before the controller body runs, so an
 * over-long input would return {@code 400 VALIDATION_ERROR} without leaving a journal entry. The
 * bound is enforced by {@code TextService.similarity} instead, so an over-long input returns
 * {@code 400 BAD_REQUEST} and is journalled as a {@code FAILED} {@code TEXT_SIMILARITY} entry. The
 * same asymmetry applies to {@code ignoreWords}: its entry caps are bean validation, so an over-cap
 * list answers {@code 400 VALIDATION_ERROR} and is <em>not</em> journalled.</p>
 *
 * <p>{@code pattern} carries exactly <em>one</em> bean-validation constraint, the scheme-independent
 * {@code @Size(max = 512)}: a longer pattern fails {@code @Valid} whatever the {@code tokenizer} is, so
 * it answers {@code 400 VALIDATION_ERROR} and is <em>not</em> journalled. The remaining pattern checks
 * stay in {@code TextService} and apply to the {@code regex} scheme alone — a missing, blank,
 * over-512-character or non-compiling pattern reaching the service directly (replay, other in-process
 * callers) still answers {@code 400 BAD_REQUEST} and is journalled as a {@code FAILED}
 * {@code TEXT_SIMILARITY} entry, while a pattern sent with any predefined scheme is ignored.</p>
 *
 * @param left          the first text to compare; must be non-blank and at most 2000 code points
 * @param right         the second text to compare; must be non-blank and at most 2000 code points
 * @param caseSensitive optional; when {@code false} or absent, both inputs are folded with
 *                      {@link java.util.Locale#ROOT} lowercasing before the distance is measured, so
 *                      {@code "Hello"} and {@code "hello"} compare identical
 * @param tokenizer     optional token scheme applied to both inputs before the code-point distance is
 *                      measured: {@code letter} (the default, the project's letter/digit run tokenizer,
 *                      i.e. exactly {@code TextService.tokenize(String)}), {@code digit} (digit runs),
 *                      {@code alphanum} (alphanumeric runs split where letters meet digits),
 *                      {@code whitespace} (non-whitespace runs) or {@code regex}. Matching is
 *                      case-insensitive on the wire; {@code null} means {@code letter}. Every scheme
 *                      <em>selects</em> tokens — everything else is discarded — and the consecutive
 *                      tokens are compared joined by one space
 * @param pattern       optional; the regular expression whose non-empty matches are the tokens when
 *                      {@code tokenizer} is {@code regex}. At most 512 characters — a longer value is a
 *                      {@code 400 VALIDATION_ERROR} for every scheme and is not journalled. Required for
 *                      the {@code regex} scheme — a missing or blank value, or one that does not compile,
 *                      is rejected as {@code 400 BAD_REQUEST} by {@code TextService}; ignored for every
 *                      predefined scheme
 * @param ignoreWords   optional words excluded from <em>both</em> inputs before the distance is
 *                      measured; at most 1000 non-blank entries of at most 100 characters each. Each
 *                      entry is matched against a whole token <em>after</em> the selected
 *                      {@code tokenizer} has chosen the tokens, and the matching uses the same folding
 *                      as the inputs: with the case-insensitive default ignoring {@code "Foo"} also
 *                      drops {@code foo} and {@code FOO}, while {@code caseSensitive=true} makes the
 *                      match an exact string comparison. An ignored token is dropped from both sides
 *                      instead of being replaced, so the joined sequences simply contain it no more
 * @param timeoutMs     optional; the wall-clock budget in milliseconds for a {@code regex} pattern
 *                      evaluation, {@code 0}..{@code 60000}. Absent, {@code null} and {@code 0} all mean
 *                      no per-request override, i.e. the configured {@code utiltools.text.regex-timeout-ms}
 *                      budget applies. A positive value may only <em>tighten</em> that budget — it is
 *                      clamped to the configured ceiling — and an exhausted budget answers
 *                      {@code 422 REGEX_TIMEOUT} with {@code details.timeoutMs} set to the budget that
 *                      actually applied. Ignored for every predefined scheme
 */
public record TextSimilarityRequest(
        @NotBlank(message = "left is required")
        String left,

        @NotBlank(message = "right is required")
        String right,

        Boolean caseSensitive,

        TokenizerScheme tokenizer,

        @Size(max = 512, message = "pattern must be at most 512 characters")
        String pattern,

        @Valid
        @Size(max = 1000, message = "ignoreWords must contain at most 1000 entries")
        List<@NotBlank(message = "ignoreWords entries must not be blank")
             @Size(max = 100, message = "each ignoreWords entry must be at most 100 characters")
             String> ignoreWords,

        @Min(value = 0, message = "timeoutMs must be at least 0")
        @Max(value = 60000, message = "timeoutMs must be at most 60000")
        Integer timeoutMs
) {

    /** Three-argument form kept for callers written before {@code tokenizer}, {@code pattern} and {@code ignoreWords} existed. */
    public TextSimilarityRequest(String left, String right, Boolean caseSensitive) {
        this(left, right, caseSensitive, null, null, null, null);
    }

    /** Five-argument form kept for callers written before {@code ignoreWords} existed; no ignore words means the pre-change result. */
    public TextSimilarityRequest(String left, String right, Boolean caseSensitive, TokenizerScheme tokenizer,
                                 String pattern) {
        this(left, right, caseSensitive, tokenizer, pattern, null, null);
    }

    /** Six-argument form kept for callers written before {@code timeoutMs} existed; no timeout means the configured budget. */
    public TextSimilarityRequest(String left, String right, Boolean caseSensitive, TokenizerScheme tokenizer,
                                 String pattern, List<String> ignoreWords) {
        this(left, right, caseSensitive, tokenizer, pattern, ignoreWords, null);
    }
}
