package com.example.utiltools.text.service;

import com.example.utiltools.text.dto.TextReplaceRequest;
import com.example.utiltools.text.dto.TextReplaceResponse;
import com.example.utiltools.text.dto.TextStatsRequest;
import com.example.utiltools.text.dto.TextStatsResponse;
import com.example.utiltools.text.dto.TextTransformRequest;
import com.example.utiltools.text.dto.TextTransformResponse;
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

    private static final String ALLOWED_OPERATIONS = "UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG";

    private static final Pattern SENTENCE_SEPARATORS = Pattern.compile("[.!?]+");

    private static final int MAX_RESULT_CHARS = 1_000_000;

    private static final String UPPER = "UPPER";
    private static final String LOWER = "LOWER";
    private static final String TITLE = "TITLE";
    private static final String CAMEL = "CAMEL";
    private static final String SNAKE = "SNAKE";
    private static final String KEBAB = "KEBAB";
    private static final String SLUG = "SLUG";

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
}
