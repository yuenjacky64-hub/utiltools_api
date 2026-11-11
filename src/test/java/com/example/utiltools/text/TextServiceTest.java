package com.example.utiltools.text;

import com.example.utiltools.text.dto.TextHtmlEntitiesRequest;
import com.example.utiltools.text.dto.TextHtmlEntitiesResponse;
import com.example.utiltools.text.dto.TextReplaceRequest;
import com.example.utiltools.text.dto.TextReplaceResponse;
import com.example.utiltools.text.dto.TextStatsRequest;
import com.example.utiltools.text.dto.TextStatsResponse;
import com.example.utiltools.text.dto.TextTransformRequest;
import com.example.utiltools.text.dto.TextTransformResponse;
import com.example.utiltools.text.dto.TextTruncateRequest;
import com.example.utiltools.text.dto.TextTruncateResponse;
import com.example.utiltools.text.service.TextService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextServiceTest {

    private final TextService service = new TextService();

    @Test
    @DisplayName("every allowed operation maps its documented input to its documented output")
    void shouldTransformEveryOperation() {
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "UPPER")))
                .isEqualTo(new TextTransformResponse("UPPER", "HELLO, WORLD!", true, 13));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "LOWER")))
                .isEqualTo(new TextTransformResponse("LOWER", "hello, world!", true, 13));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "TITLE")))
                .isEqualTo(new TextTransformResponse("TITLE", "Hello World", true, 11));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "CAMEL")))
                .isEqualTo(new TextTransformResponse("CAMEL", "helloWorld", true, 10));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "SNAKE")))
                .isEqualTo(new TextTransformResponse("SNAKE", "hello_world", true, 11));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "KEBAB")))
                .isEqualTo(new TextTransformResponse("KEBAB", "hello-world", true, 11));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "SLUG")))
                .isEqualTo(new TextTransformResponse("SLUG", "hello-world", true, 11));
        assertThat(service.transform(new TextTransformRequest("Hello, World!", "SENTENCE_CASE")))
                .isEqualTo(new TextTransformResponse("SENTENCE_CASE", "Hello, world!", true, 13));

        // case-insensitive operation, echoed back upper-case
        assertThat(service.transform(new TextTransformRequest("Hello, World!", " snake ")))
                .isEqualTo(new TextTransformResponse("SNAKE", "hello_world", true, 11));

        // TITLE case-titles every token, so interior capitalisation is normalised
        assertThat(service.transform(new TextTransformRequest("hello wORLD", "TITLE")).result())
                .isEqualTo("Hello World");

        // UPPER/LOWER keep punctuation and spacing exactly as supplied
        assertThat(service.transform(new TextTransformRequest("  a  b  ", "UPPER")).result())
                .isEqualTo("  A  B  ");
    }

    @Test
    @DisplayName("SLUG strips diacritics via NFD and joins with a single hyphen")
    void shouldSlugDiacritics() {
        TextTransformResponse response = service.transform(new TextTransformRequest("Café déjà vu!", "SLUG"));

        assertThat(response.operation()).isEqualTo("SLUG");
        assertThat(response.result()).isEqualTo("cafe-deja-vu");
        assertThat(response.changed()).isTrue();
        assertThat(response.length()).isEqualTo(12);

        // combining marks never leave empty or doubled hyphens behind
        assertThat(service.transform(new TextTransformRequest("  Ünïcode — Tèst  ", "SLUG")).result())
                .isEqualTo("unicode-test");
    }

    @Test
    @DisplayName("stats count code points, including surrogate pairs, and split lines and sentences")
    void shouldCountMultilineEmojiStats() {
        TextStatsResponse response = service.stats(new TextStatsRequest("Line one.\nTwo 😀 lines! Bye?"));

        assertThat(response).isEqualTo(new TextStatsResponse(27, 22, 5, 2, 3));

        // an emoji is one code point (two UTF-16 chars) and zero words
        TextStatsResponse emoji = service.stats(new TextStatsRequest("😀"));
        assertThat(emoji.characters()).isEqualTo(1);
        assertThat(emoji.charactersNoWhitespace()).isEqualTo(1);
        assertThat(emoji.words()).isZero();
        assertThat(emoji.lines()).isEqualTo(1);
        assertThat(emoji.sentences()).isEqualTo(1);

        // CRLF and CR normalise to the same line count
        assertThat(service.stats(new TextStatsRequest("a\r\nb\rc")).lines()).isEqualTo(3);
    }

    @Test
    @DisplayName("blank text is rejected by the request contract")
    void shouldRejectBlankText() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<ConstraintViolation<TextTransformRequest>> transformViolations =
                    validator.validate(new TextTransformRequest("   ", "SNAKE"));
            assertThat(transformViolations).hasSize(1);
            assertThat(transformViolations).extracting(ConstraintViolation::getMessage)
                    .contains("text is required");

            Set<ConstraintViolation<TextTransformRequest>> operationViolations =
                    validator.validate(new TextTransformRequest("Hello", " "));
            assertThat(operationViolations).extracting(ConstraintViolation::getMessage)
                    .containsExactlyInAnyOrder("operation is required",
                            "Supported operations: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE");

            Set<ConstraintViolation<TextStatsRequest>> statsViolations =
                    validator.validate(new TextStatsRequest(""));
            assertThat(statsViolations).extracting(ConstraintViolation::getMessage)
                    .containsExactly("text is required");
        }
    }

    @Test
    @DisplayName("an unknown operation still fails at the service layer and echoes only the operation value")
    void shouldRejectUnknownOperation() {
        assertThatThrownBy(() -> service.transform(new TextTransformRequest("secret body", "shout")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported operation 'shout'. Allowed values: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE")
                .hasMessageNotContaining("secret body");
    }

    @Test
    @DisplayName("SENTENCE_CASE upper-cases each sentence's first letter and preserves punctuation")
    void shouldSentenceCasePreservingPunctuation() {
        assertThat(service.transform(new TextTransformRequest("hello WORLD. bye world! third ONE?", "SENTENCE_CASE")))
                .isEqualTo(new TextTransformResponse("SENTENCE_CASE", "Hello world. Bye world! Third one?", true, 34));
    }

    @Test
    @DisplayName("SENTENCE_CASE is idempotent: canonical text and its own result are unchanged")
    void shouldSentenceCaseLeaveCanonicalTextUnchanged() {
        TextTransformResponse first = service.transform(
                new TextTransformRequest("Hello world. Another one!", "SENTENCE_CASE"));
        assertThat(first.result()).isEqualTo("Hello world. Another one!");
        assertThat(first.changed()).isFalse();

        TextTransformResponse second = service.transform(
                new TextTransformRequest(first.result(), "SENTENCE_CASE"));
        assertThat(second.result()).isEqualTo(first.result());
        assertThat(second.changed()).isFalse();
    }

    @Test
    @DisplayName("SENTENCE_CASE maps Unicode under Locale.ROOT and treats [.!?] runs as boundaries")
    void shouldSentenceCaseUnicodeAndSeparators() {
        assertThat(service.transform(new TextTransformRequest("ça va. ÉTÉ! \"quoted start", "SENTENCE_CASE")).result())
                .isEqualTo("Ça va. Été! \"Quoted start");
        assertThat(service.transform(new TextTransformRequest("hello...world", "SENTENCE_CASE")).result())
                .isEqualTo("Hello...World");
        assertThat(service.transform(new TextTransformRequest("hello\nworld", "SENTENCE_CASE")).result())
                .isEqualTo("Hello\nworld");
    }

    @Test
    @DisplayName("changed and length are computed against the result in code points")
    void shouldReportChangedAndLengthInCodePoints() {
        TextTransformResponse upper = service.transform(new TextTransformRequest("HELLO", "UPPER"));
        assertThat(upper.result()).isEqualTo("HELLO");
        assertThat(upper.changed()).isFalse();
        assertThat(upper.length()).isEqualTo(5);

        TextTransformResponse slug = service.transform(new TextTransformRequest("Café", "SLUG"));
        assertThat(slug.result()).isEqualTo("cafe");
        assertThat(slug.length()).isEqualTo(4);

        TextTransformResponse emoji = service.transform(new TextTransformRequest("😀 Hello", "SNAKE"));
        assertThat(emoji.result()).isEqualTo("hello");
        assertThat(emoji.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("regex mode expands $n group references for every match")
    void shouldReplaceRegexWithGroupReferences() {
        assertThat(service.replace(new TextReplaceRequest("a1 b2", "(\\w)(\\d)", "$2$1", null, null, null)))
                .isEqualTo(new TextReplaceResponse("1a 2b", 2, true, 5));
    }

    @Test
    @DisplayName("literal mode replaces regex metacharacters verbatim")
    void shouldReplaceLiteralMetacharacters() {
        assertThat(service.replace(new TextReplaceRequest("a.b", ".", "X", null, true, null)))
                .isEqualTo(new TextReplaceResponse("aXb", 1, true, 3));
    }

    @Test
    @DisplayName("all=false replaces only the first match")
    void shouldReplaceFirstMatchOnlyWhenAllFalse() {
        assertThat(service.replace(new TextReplaceRequest("aaa", "a", "b", false, null, null)))
                .isEqualTo(new TextReplaceResponse("baa", 1, true, 3));
    }

    @Test
    @DisplayName("caseInsensitive=true matches regardless of case")
    void shouldReplaceCaseInsensitively() {
        assertThat(service.replace(new TextReplaceRequest("Hello HELLO", "hello", "bye", null, null, true)))
                .isEqualTo(new TextReplaceResponse("bye bye", 2, true, 7));
    }

    @Test
    @DisplayName("a no-match request leaves the text unchanged")
    void shouldReportNoMatchUnchanged() {
        assertThat(service.replace(new TextReplaceRequest("xyz", "q", "b", null, null, null)))
                .isEqualTo(new TextReplaceResponse("xyz", 0, false, 3));
    }

    @Test
    @DisplayName("an invalid regex fails with a BAD_REQUEST-shaped message")
    void shouldRejectInvalidRegex() {
        assertThatThrownBy(() -> service.replace(new TextReplaceRequest("abc", "(", "x", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid regex pattern '('");
    }

    @Test
    @DisplayName("length measures code points of the result")
    void shouldMeasureCodePointLengthAfterReplace() {
        assertThat(service.replace(new TextReplaceRequest("\uD83D\uDE00a\uD83D\uDE00", "\uD83D\uDE00", "", null, null, null)))
                .isEqualTo(new TextReplaceResponse("a", 2, true, 1));
    }

    @Test
    @DisplayName("TextReplaceRequest constraint violations fire on every bound")
    void shouldValidateReplaceRequest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new TextReplaceRequest("   ", "a", "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .contains("text is required");

            assertThat(validator.validate(new TextReplaceRequest("abc", " ", "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("pattern is required");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a", null, null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("replacement is required");

            assertThat(validator.validate(new TextReplaceRequest("a".repeat(100001), "a", "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("text must be at most 100000 characters");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a".repeat(513), "b", null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("pattern must be at most 512 characters");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a", "b".repeat(100001), null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("replacement must be at most 100000 characters");

            assertThat(validator.validate(new TextReplaceRequest("abc", "a", "b", null, null, null))).isEmpty();
        }
    }

    @Test
    @DisplayName("an oversized replacement result is rejected before it is materialised")
    void shouldRejectOversizedReplacementResult() {
        assertThatThrownBy(() -> service.replace(
                new TextReplaceRequest("a".repeat(2000), "a", "b".repeat(2000), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("replacement result exceeds 1000000 characters");
    }

    @Test
    @DisplayName("a group reference that would expand past the bound is rejected without expanding")
    void shouldRejectOversizedGroupReferenceExpansion() {
        String text = "a".repeat(100000);
        String pattern = "(a{100000})";
        String replacement = "$1".repeat(50000);

        assertThatThrownBy(() -> service.replace(new TextReplaceRequest(text, pattern, replacement, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("replacement result exceeds 1000000 characters");
    }

    @Test
    @DisplayName("all=false is bounded by the first match, not by every match")
    void shouldAcceptFirstMatchOnlyWithLargeReplacement() {
        TextReplaceResponse response = service.replace(
                new TextReplaceRequest("a".repeat(100000), "a", "b".repeat(11), false, null, null));

        assertThat(response.replacements()).isEqualTo(1);
        assertThat(response.changed()).isTrue();
        assertThat(response.result()).startsWith("b".repeat(11) + "a");
        assertThat(response.length()).isEqualTo(100010);
    }

    @Test
    @DisplayName("ENCODE escapes & first and then the remaining four HTML-significant characters")
    void shouldEncodeHtmlEntitiesAmpersandFirst() {
        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("ENCODE", "<a href=\"x\">Fish & chips 'n' more</a>")))
                .isEqualTo(new TextHtmlEntitiesResponse("ENCODE",
                        "&lt;a href=&quot;x&quot;&gt;Fish &amp; chips &#39;n&#39; more&lt;/a&gt;", true, 71));

        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("ENCODE", "&amp;")))
                .isEqualTo(new TextHtmlEntitiesResponse("ENCODE", "&amp;amp;", true, 9));

        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("ENCODE", "plain text")))
                .isEqualTo(new TextHtmlEntitiesResponse("ENCODE", "plain text", false, 10));
    }

    @Test
    @DisplayName("DECODE resolves the five named entities plus decimal and hexadecimal numeric references")
    void shouldDecodeNamedAndNumericReferences() {
        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("DECODE",
                "&amp; &lt; &gt; &quot; &#39;")))
                .isEqualTo(new TextHtmlEntitiesResponse("DECODE", "& < > \" '", true, 9));

        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("DECODE", "&#65;&#x42;&#X43;&#00071;")))
                .isEqualTo(new TextHtmlEntitiesResponse("DECODE", "ABCG", true, 4));

        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("DECODE", "&#x1F600;")))
                .isEqualTo(new TextHtmlEntitiesResponse("DECODE", "\uD83D\uDE00", true, 1));

        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("DECODE", "Caf&#233; &#x2014; ok")))
                .isEqualTo(new TextHtmlEntitiesResponse("DECODE", "Café — ok", true, 9));
    }

    @Test
    @DisplayName("DECODE preserves unknown and malformed references verbatim")
    void shouldPreserveUnknownAndMalformedReferences() {
        String text = "&nbsp; &AMP; &copy 5 & &#; &#x; &#0; &#xD800; &#9999999; &#xFFFFFF; bare & end";
        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("DECODE", text)))
                .isEqualTo(new TextHtmlEntitiesResponse("DECODE", text, false, text.length()));
    }

    @Test
    @DisplayName("decode(encode(text)) round-trips without double-decoding")
    void shouldRoundTripEncodeThenDecode() {
        String text = "<script>alert(\"it's & <fine>\");</script> 100% ✓";
        String encoded = service.htmlEntities(new TextHtmlEntitiesRequest("ENCODE", text)).result();

        assertThat(encoded).doesNotContain("<", ">", "\"", "'").contains("&amp;");
        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("DECODE", encoded)).result()).isEqualTo(text);
    }

    @Test
    @DisplayName("the action is matched case-insensitively, echoed verbatim and rejects anything else")
    void shouldMatchActionCaseInsensitivelyAndRejectUnknownActions() {
        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest(" encode ", "&")))
                .isEqualTo(new TextHtmlEntitiesResponse(" encode ", "&amp;", true, 5));
        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("decode", "&amp;")))
                .isEqualTo(new TextHtmlEntitiesResponse("decode", "&", true, 1));

        assertThatThrownBy(() -> service.htmlEntities(new TextHtmlEntitiesRequest("STRIP", "<b>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported action: STRIP. Allowed values: ENCODE, DECODE");
    }

    @Test
    @DisplayName("an html-entity result that would exceed the bound is rejected before it is built")
    void shouldRejectOversizedHtmlEntityResult() {
        assertThatThrownBy(() -> service.htmlEntities(
                new TextHtmlEntitiesRequest("ENCODE", "&".repeat(210_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("html-entity result exceeds 1000000 characters");

        assertThat(service.htmlEntities(new TextHtmlEntitiesRequest("ENCODE", "&".repeat(200_000))).length())
                .isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("the html-entities request validates action and text like the other text DTOs")
    void shouldValidateHtmlEntitiesRequest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new TextHtmlEntitiesRequest(" ", "abc")))
                    .extracting(ConstraintViolation::getMessage)
                    .contains("action is required");

            assertThat(validator.validate(new TextHtmlEntitiesRequest("SHRUG", "abc")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("action must be either ENCODE or DECODE");

            assertThat(validator.validate(new TextHtmlEntitiesRequest("encode", "   ")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("text is required");

            assertThat(validator.validate(new TextHtmlEntitiesRequest("Decode", "&amp;"))).isEmpty();
        }
    }

    @Test
    @DisplayName("truncate - text within maxLength is returned unchanged with truncated=false")
    void shouldReturnUnchangedWhenInputFits() {
        TextTruncateResponse response = service.truncate(new TextTruncateRequest("Hello", 10, null, null));

        assertThat(response.result()).isEqualTo("Hello");
        assertThat(response.truncated()).isFalse();
        assertThat(response.originalLength()).isEqualTo(5);
        assertThat(response.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("truncate - a fitting input is not rejected even when the ellipsis is longer than maxLength")
    void shouldNotValidateEllipsisWhenInputFits() {
        TextTruncateResponse response = service.truncate(new TextTruncateRequest("a", 1, "12345", null));

        assertThat(response.result()).isEqualTo("a");
        assertThat(response.truncated()).isFalse();
    }

    @Test
    @DisplayName("truncate - a hard cut with a null ellipsis defaults to '…'")
    void shouldHardCutWithDefaultEllipsis() {
        TextTruncateResponse response =
                service.truncate(new TextTruncateRequest("abcdefghij", 5, null, Boolean.FALSE));

        assertThat(response.result()).isEqualTo("abcd…");
        assertThat(response.truncated()).isTrue();
        assertThat(response.originalLength()).isEqualTo(10);
        assertThat(response.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("truncate - a hard cut with a custom ellipsis")
    void shouldHardCutWithCustomEllipsis() {
        TextTruncateResponse response =
                service.truncate(new TextTruncateRequest("abcdefghij", 5, "...", Boolean.FALSE));

        assertThat(response.result()).isEqualTo("ab...");
        assertThat(response.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("truncate - a word-boundary cut trims the trailing whitespace of the cut")
    void shouldCutAtWordBoundaryAndTrimTrailingWhitespace() {
        // "Hello  World" is 12 code points; available = 9 - 3 = 6, so the boundary at the
        // double space is kept and both spaces are trimmed from the cut.
        TextTruncateResponse response =
                service.truncate(new TextTruncateRequest("Hello  World", 9, "...", Boolean.TRUE));

        assertThat(response.result()).isEqualTo("Hello...");
        assertThat(response.length()).isEqualTo(8);
    }

    @Test
    @DisplayName("truncate - a word-boundary cut picks the last whitespace at or before the limit")
    void shouldPickLastWhitespaceAtOrBeforeLimit() {
        // "aa bb cc dd" is 11 code points; the null ellipsis is '…' (1), so available = 8 - 1 = 7
        // and the last whitespace at or before index 7 sits at index 5.
        TextTruncateResponse response =
                service.truncate(new TextTruncateRequest("aa bb cc dd", 8, null, Boolean.TRUE));

        assertThat(response.result()).isEqualTo("aa bb…");
        assertThat(response.originalLength()).isEqualTo(11);
        assertThat(response.length()).isEqualTo(6);
    }

    @Test
    @DisplayName("truncate - a word-boundary cut falls back to a hard cut when no whitespace fits")
    void shouldFallBackToHardCutWhenNoWhitespaceFits() {
        TextTruncateResponse response =
                service.truncate(new TextTruncateRequest("abcdefghij", 5, "...", Boolean.TRUE));

        assertThat(response.result()).isEqualTo("ab...");
        assertThat(response.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("truncate - an empty ellipsis truncates without a marker")
    void shouldUseNoMarkerForEmptyEllipsis() {
        TextTruncateResponse response =
                service.truncate(new TextTruncateRequest("abcdefghij", 5, "", Boolean.FALSE));

        assertThat(response.result()).isEqualTo("abcde");
        assertThat(response.truncated()).isTrue();
        assertThat(response.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("truncate - a cut never splits a surrogate pair")
    void shouldKeepSurrogatePairsIntact() {
        // "ab😀cd😀ef" is 8 code points; maxLength 4 with the default ellipsis leaves 3 for content.
        TextTruncateResponse response =
                service.truncate(new TextTruncateRequest("ab😀cd😀ef", 4, null, null));

        assertThat(response.result()).isEqualTo("ab😀…");
        assertThat(response.originalLength()).isEqualTo(8);
        assertThat(response.length()).isEqualTo(4);
    }

    @Test
    @DisplayName("truncate - a maxLength that cannot hold content next to the ellipsis is rejected")
    void shouldRejectMaxLengthNotExceedingEllipsis() {
        assertThatThrownBy(() -> service.truncate(new TextTruncateRequest("abcdef", 1, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLength must exceed the ellipsis length");

        assertThatThrownBy(() -> service.truncate(new TextTruncateRequest("abcdef", 5, "12345678", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLength must exceed the ellipsis length");
    }
}
