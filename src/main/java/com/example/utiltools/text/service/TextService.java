package com.example.utiltools.text.service;

import com.example.utiltools.text.dto.TextReplaceRequest;
import com.example.utiltools.text.dto.TextReplaceResponse;
import com.example.utiltools.text.dto.TextHtmlEntitiesRequest;
import com.example.utiltools.text.dto.TextHtmlEntitiesResponse;
import com.example.utiltools.text.dto.TextStatsRequest;
import com.example.utiltools.text.dto.TextStatsResponse;
import com.example.utiltools.text.dto.TextTransformRequest;
import com.example.utiltools.text.dto.TextTransformResponse;
import com.example.utiltools.text.dto.TextTruncateRequest;
import com.example.utiltools.text.dto.TextTruncateResponse;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic text transformation, find-and-replace and statistics.
 *
 * <p>Every operation is a pure function of the request body: only {@link Locale#ROOT} case
 * mapping is used and no clock, locale or random source is read, so a journalled result always
 * matches its replay. Word tokenization is the single {@link #tokenize(String)} method: a token is
 * a maximal run of code points for which {@link Character#isLetterOrDigit(int)} is true.</p>
 */
@Service
public class TextService {

    private static final String ALLOWED_OPERATIONS = "UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE";

    private static final Pattern SENTENCE_SEPARATORS = Pattern.compile("[.!?]+");

    private static final int MAX_RESULT_CHARS = 1_000_000;

    private static final String ALLOWED_ACTIONS = "ENCODE, DECODE";

    private static final String ENCODE = "ENCODE";

    private static final String DECODE = "DECODE";

    /** Longest character reference body accepted by {@link #decodeReference(String, int)}: {@code #9999999}. */
    private static final int MAX_REFERENCE_BODY = 8;

    private static final String UPPER = "UPPER";
    private static final String LOWER = "LOWER";
    private static final String TITLE = "TITLE";
    private static final String CAMEL = "CAMEL";
    private static final String SNAKE = "SNAKE";
    private static final String KEBAB = "KEBAB";
    private static final String SLUG = "SLUG";
    private static final String SENTENCE_CASE = "SENTENCE_CASE";

    public TextTransformResponse transform(TextTransformRequest request) {
        String text = request.text();
        String operation = request.operation().trim().toUpperCase(Locale.ROOT);
        String result = switch (operation) {
            case UPPER -> text.toUpperCase(Locale.ROOT);
            case LOWER -> text.toLowerCase(Locale.ROOT);
            case TITLE -> joinTransformed(text, " ", TextService::titleCase);
            case CAMEL -> camelCase(text);
            case SNAKE -> joinTransformed(text, "_", TextService::lowerCase);
            case KEBAB -> joinTransformed(text, "-", TextService::lowerCase);
            case SLUG -> slug(text);
            case SENTENCE_CASE -> sentenceCase(text);
            default -> throw new IllegalArgumentException("Unsupported operation '" + request.operation()
                    + "'. Allowed values: " + ALLOWED_OPERATIONS);
        };
        boolean changed = !result.equals(text);
        int length = result.codePointCount(0, result.length());
        return new TextTransformResponse(operation, result, changed, length);
    }

    public TextReplaceResponse replace(TextReplaceRequest request) {
        String text = request.text();
        boolean all = request.all() == null || request.all();
        boolean literal = Boolean.TRUE.equals(request.literal());
        boolean caseInsensitive = Boolean.TRUE.equals(request.caseInsensitive());
        String source = literal ? Pattern.quote(request.pattern()) : request.pattern();
        Pattern compiled;
        try {
            // CASE_INSENSITIVE only: without UNICODE_CASE this folds ASCII letters only, which is
            // the documented (and plan-pinned) behaviour; UNICODE_CASE is out of scope.
            compiled = Pattern.compile(source, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern '" + request.pattern()
                    + "': " + e.getDescription());
        }
        Matcher counter = compiled.matcher(text);
        int matches = 0;
        while (counter.find()) {
            matches++;
        }
        int replacements = all ? matches : Math.min(matches, 1);
        String replacement = literal
                ? Matcher.quoteReplacement(request.replacement())
                : request.replacement();
        // The result is accumulated match by match and every append is size-checked against the
        // exact expanded length of that match's replacement, so a `$n` group reference cannot
        // inflate the result past MAX_RESULT_CHARS before the check runs. The bound is therefore
        // enforced *instead of* materialising an oversized string.
        StringBuilder builder = new StringBuilder(Math.min(MAX_RESULT_CHARS, text.length() + 16));
        Matcher matcher = compiled.matcher(text);
        int appended = 0;
        int position = 0;
        while (appended < replacements && matcher.find()) {
            long expanded;
            try {
                expanded = expandedReplacementLength(matcher, replacement);
            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                throw invalidReplacement(request, e);
            }
            long projected = (long) builder.length() + (matcher.start() - position) + expanded;
            if (projected > MAX_RESULT_CHARS) {
                throw new IllegalArgumentException("replacement result exceeds " + MAX_RESULT_CHARS + " characters");
            }
            try {
                matcher.appendReplacement(builder, replacement);
            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                throw invalidReplacement(request, e);
            }
            position = matcher.end();
            appended++;
        }
        if ((long) builder.length() + (text.length() - position) > MAX_RESULT_CHARS) {
            throw new IllegalArgumentException("replacement result exceeds " + MAX_RESULT_CHARS + " characters");
        }
        matcher.appendTail(builder);
        String result = builder.toString();
        return new TextReplaceResponse(result, replacements, !result.equals(text),
                result.codePointCount(0, result.length()));
    }

    /**
     * Exact length the {@code template} contributes for the matcher's current match, following the
     * {@link Matcher#appendReplacement(StringBuilder, String)} group-reference rules ({@code $n},
     * {@code ${name}} and {@code \x} escapes; a group that did not participate contributes nothing).
     * Computed without building the expansion so the caller can enforce {@link #MAX_RESULT_CHARS}
     * before anything is allocated.
     */
    private static long expandedReplacementLength(Matcher matcher, String template) {
        long length = 0;
        int size = template.length();
        int cursor = 0;
        while (cursor < size) {
            char current = template.charAt(cursor);
            if (current == '\\') {
                if (cursor + 1 == size) {
                    throw new IllegalArgumentException("character to be escaped is missing");
                }
                length++;
                cursor += 2;
            } else if (current == '$') {
                cursor++;
                if (cursor == size) {
                    throw new IllegalArgumentException("Illegal group reference: group index is missing");
                }
                char next = template.charAt(cursor);
                if (next == '{') {
                    int close = template.indexOf('}', cursor);
                    if (close < 0) {
                        throw new IllegalArgumentException("named capturing group is missing trailing '}'");
                    }
                    String group = matcher.group(template.substring(cursor + 1, close));
                    length += group == null ? 0 : group.length();
                    cursor = close + 1;
                } else {
                    int groupNumber = next - '0';
                    if (groupNumber < 0 || groupNumber > 9) {
                        throw new IllegalArgumentException("Illegal group reference");
                    }
                    cursor++;
                    while (cursor < size) {
                        int digit = template.charAt(cursor) - '0';
                        if (digit < 0 || digit > 9) {
                            break;
                        }
                        int candidate = groupNumber * 10 + digit;
                        if (candidate > matcher.groupCount()) {
                            break;
                        }
                        groupNumber = candidate;
                        cursor++;
                    }
                    String group = matcher.group(groupNumber);
                    length += group == null ? 0 : group.length();
                }
            } else {
                length++;
                cursor++;
            }
        }
        return length;
    }

    private static IllegalArgumentException invalidReplacement(TextReplaceRequest request, Exception cause) {
        return new IllegalArgumentException("Invalid replacement '" + request.replacement()
                + "' for pattern '" + request.pattern() + "': " + cause.getMessage());
    }

    /**
     * Encodes or decodes HTML character references in a single left-to-right pass.
     *
     * <p>{@code ENCODE} escapes {@code &} first and then {@code <}, {@code >}, {@code "} and
     * {@code '} to {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;} and {@code &#39;}.
     * {@code DECODE} resolves those five named entities plus decimal ({@code &#NN;}) and
     * hexadecimal ({@code &#xHH;}) numeric references; every other reference is preserved
     * verbatim, so decoding is total over its input. Produced text is never re-scanned, so
     * {@code &amp;lt;} decodes to the literal {@code &lt;}. The result is bounded by
     * {@link #MAX_RESULT_CHARS} and the bound is enforced before each append.</p>
     */
    public TextHtmlEntitiesResponse htmlEntities(TextHtmlEntitiesRequest request) {
        String text = request.text();
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        String result = switch (action) {
            case ENCODE -> encodeHtmlEntities(text);
            case DECODE -> decodeHtmlEntities(text);
            default -> throw new IllegalArgumentException("Unsupported action: " + request.action()
                    + ". Allowed values: " + ALLOWED_ACTIONS);
        };
        return new TextHtmlEntitiesResponse(request.action(), result, !result.equals(text),
                result.codePointCount(0, result.length()));
    }

    /** One left-to-right pass; {@code &} is escaped first and produced text is never re-scanned. */
    private static String encodeHtmlEntities(String text) {
        StringBuilder builder = new StringBuilder(Math.min(MAX_RESULT_CHARS, text.length() + 16));
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            String escape = switch (current) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (escape == null) {
                appendWithinBound(builder, current);
            } else {
                appendWithinBound(builder, escape);
            }
        }
        return builder.toString();
    }

    /** One left-to-right pass; unknown or malformed references are copied through verbatim. */
    private static String decodeHtmlEntities(String text) {
        StringBuilder builder = new StringBuilder(Math.min(MAX_RESULT_CHARS, text.length() + 16));
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) != '&') {
                appendWithinBound(builder, text.charAt(index));
                index++;
                continue;
            }
            DecodedReference decoded = decodeReference(text, index);
            if (decoded == null) {
                appendWithinBound(builder, '&');
                index++;
            } else {
                appendWithinBound(builder, decoded.value());
                index = decoded.next();
            }
        }
        return builder.toString();
    }

    /**
     * Matches one character reference starting at {@code start} (which must hold {@code '&'}).
     * Returns {@code null} — meaning "copy the {@code &} verbatim and continue" — for an unknown
     * name, a missing {@code ;}, an invalid or out-of-range numeric value and every other
     * malformed reference, so decoding never throws on content.
     */
    private static DecodedReference decodeReference(String text, int start) {
        int limit = Math.min(text.length(), start + 2 + MAX_REFERENCE_BODY);
        int cursor = start + 1;
        while (cursor < limit && text.charAt(cursor) != ';') {
            char current = text.charAt(cursor);
            boolean digit = current >= '0' && current <= '9';
            boolean letter = (current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z');
            boolean marker = current == '#' || (cursor == start + 2 && text.charAt(start + 1) == '#'
                    && (current == 'x' || current == 'X'));
            if (!digit && !letter && !marker) {
                return null;
            }
            cursor++;
        }
        if (cursor >= text.length() || text.charAt(cursor) != ';') {
            return null;
        }
        String body = text.substring(start + 1, cursor);
        String decoded = switch (body) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            default -> body.startsWith("#") ? decodeNumericReference(body) : null;
        };
        return decoded == null ? null : new DecodedReference(decoded, cursor + 1);
    }

    /** Decodes {@code #NN} / {@code #xHH} bodies, rejecting zero, surrogates and out-of-range values. */
    private static String decodeNumericReference(String body) {
        boolean hex = body.length() > 2 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
        String digits = body.substring(hex ? 2 : 1);
        if (digits.isEmpty() || digits.length() > (hex ? 6 : 7)) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(digits, hex ? 16 : 10);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value <= 0 || value > Character.MAX_CODE_POINT
                || (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE)) {
            return null;
        }
        return new String(Character.toChars(value));
    }

    /** Enforces {@link #MAX_RESULT_CHARS} before the append, so an oversized result is never built. */
    private static void appendWithinBound(StringBuilder builder, String chunk) {
        if ((long) builder.length() + chunk.length() > MAX_RESULT_CHARS) {
            throw new IllegalArgumentException("html-entity result exceeds " + MAX_RESULT_CHARS + " characters");
        }
        builder.append(chunk);
    }

    private static void appendWithinBound(StringBuilder builder, char character) {
        if (builder.length() + 1 > MAX_RESULT_CHARS) {
            throw new IllegalArgumentException("html-entity result exceeds " + MAX_RESULT_CHARS + " characters");
        }
        builder.append(character);
    }

    /** One matched character reference: the decoded text plus the index just past its {@code ';'}. */
    private record DecodedReference(String value, int next) {}

    public TextStatsResponse stats(TextStatsRequest request) {
        String text = request.text();
        int characters = text.codePointCount(0, text.length());
        int charactersNoWhitespace = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                charactersNoWhitespace++;
            }
            index += Character.charCount(codePoint);
        }
        int words = tokenize(text).size();
        int lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).length;
        int sentences = countSentences(text);
        return new TextStatsResponse(characters, charactersNoWhitespace, words, lines, sentences);
    }

    public TextTruncateResponse truncate(TextTruncateRequest request) {
        String text = request.text();
        int total = text.codePointCount(0, text.length());
        int maxLength = request.maxLength();
        if (total <= maxLength) {
            return new TextTruncateResponse(text, false, total, total);
        }
        // A null ellipsis defaults to the single code point "…"; an empty one means no marker.
        String marker = request.ellipsis() == null ? "…" : request.ellipsis();
        int markerLength = marker.codePointCount(0, marker.length());
        if (maxLength <= markerLength) {
            // Content would remain after step 2, so the result could not hold any content: it would
            // exceed maxLength or consist of nothing but the marker.
            throw new IllegalArgumentException(
                    "maxLength must exceed the ellipsis length so the truncated result can contain content");
        }
        int available = maxLength - markerLength;
        // Whole-code-point slicing by construction: a code-point array slice can never split a
        // surrogate pair, and cut + markerLength <= maxLength keeps the result within the bound.
        int[] codePoints = text.codePoints().toArray();
        int cut = wordBoundaryCut(codePoints, available, Boolean.TRUE.equals(request.wordBoundary()));
        String result = new String(codePoints, 0, cut) + marker;
        return new TextTruncateResponse(result, true, total, result.codePointCount(0, result.length()));
    }

    /**
     * Index just past the prefix to keep. A hard cut at {@code available} unless word-boundary
     * mode finds the last whitespace code point at or before the limit; the trailing whitespace of
     * that cut is trimmed, and when no whitespace fits or the boundary cut would be empty the hard
     * cut is used instead.
     */
    private static int wordBoundaryCut(int[] codePoints, int available, boolean wordBoundary) {
        if (!wordBoundary) {
            return available;
        }
        int boundary = -1;
        for (int index = available - 1; index >= 0; index--) {
            if (Character.isWhitespace(codePoints[index])) {
                boundary = index;
                break;
            }
        }
        int cut = boundary + 1;
        while (cut > 0 && Character.isWhitespace(codePoints[cut - 1])) {
            cut--;
        }
        return cut > 0 ? cut : available;
    }

    /** Maximal runs of {@link Character#isLetterOrDigit(int)} code points. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            index += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int countSentences(String text) {
        int sentences = 0;
        for (String segment : SENTENCE_SEPARATORS.split(text, -1)) {
            if (!segment.isBlank()) {
                sentences++;
            }
        }
        return sentences;
    }

    private static String joinTransformed(String text, String separator,
                                          java.util.function.UnaryOperator<String> mapper) {
        List<String> parts = new ArrayList<>();
        for (String token : tokenize(text)) {
            parts.add(mapper.apply(token));
        }
        return String.join(separator, parts);
    }

    private static String camelCase(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(lowerCase(tokens.get(0)));
        for (int index = 1; index < tokens.size(); index++) {
            result.append(capitalizeFirst(tokens.get(index)));
        }
        return result.toString();
    }

    private static String slug(String text) {
        List<String> parts = new ArrayList<>();
        for (String token : tokenize(text)) {
            String ascii = stripDiacritics(lowerCase(token));
            if (!ascii.isEmpty()) {
                parts.add(ascii);
            }
        }
        return String.join("-", parts);
    }

    private static String stripDiacritics(String token) {
        String decomposed = Normalizer.normalize(token, Normalizer.Form.NFD);
        StringBuilder stripped = new StringBuilder();
        for (int index = 0; index < decomposed.length(); ) {
            int codePoint = decomposed.codePointAt(index);
            if (!isCombiningMark(codePoint)) {
                stripped.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return stripped.toString();
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static String titleCase(String token) {
        return capitalizeFirst(token);
    }

    private static String capitalizeFirst(String token) {
        if (token.isEmpty()) {
            return token;
        }
        int first = token.codePointAt(0);
        int rest = Character.charCount(first);
        return new String(Character.toChars(first)).toUpperCase(Locale.ROOT)
                + token.substring(rest).toLowerCase(Locale.ROOT);
    }

    private static String lowerCase(String token) {
        return token.toLowerCase(Locale.ROOT);
    }

    /**
     * Sentence case: the first letter of each sentence is upper-cased, every other letter in that
     * sentence is lower-cased, and every non-letter is copied through unchanged. A sentence ends at
     * each {@link #SENTENCE_SEPARATORS} match; word boundaries use the same predicate as
     * {@link #tokenize(String)}. Locale.ROOT only, so a journalled result always replays exactly.
     */
    private static String sentenceCase(String text) {
        StringBuilder result = new StringBuilder(text.length());
        Matcher separators = SENTENCE_SEPARATORS.matcher(text);
        boolean newSentence = true;
        int cursor = 0;
        while (true) {
            boolean found = separators.find();
            int end = found ? separators.start() : text.length();
            newSentence = appendSentenceSegment(result, text.substring(cursor, end), newSentence);
            if (!found) {
                return result.toString();
            }
            result.append(text, separators.start(), separators.end());
            newSentence = true;
            cursor = separators.end();
        }
    }

    /** Copies one separator-free segment, upper-casing its first letter/digit when starting a sentence. */
    private static boolean appendSentenceSegment(StringBuilder result, String segment, boolean startSentence) {
        boolean newSentence = startSentence;
        for (int index = 0; index < segment.length(); ) {
            int codePoint = segment.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            if (Character.isLetterOrDigit(codePoint)) {
                result.append(newSentence ? character.toUpperCase(Locale.ROOT) : character.toLowerCase(Locale.ROOT));
                newSentence = false;
            } else {
                result.append(character);
            }
            index += Character.charCount(codePoint);
        }
        return newSentence;
    }
}
