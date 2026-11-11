package com.example.utiltools.text.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
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
import com.example.utiltools.text.dto.TextWordFrequencyRequest;
import com.example.utiltools.text.dto.TextWordFrequencyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/text")
@Tag(name = "Text Transformation & Statistics Utility", description = "Endpoints for deterministic case/format conversion, find-and-replace, HTML entity coding and text statistics")
public class TextController {

    private final AuditedOperations auditedOperations;

    public TextController(AuditedOperations auditedOperations) {
        this.auditedOperations = auditedOperations;
    }

    @PostMapping("/transform")
    @Operation(summary = "Transform text case and format",
            description = "Applies one deterministic operation to the supplied text: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG or SENTENCE_CASE. "
                    + "The response echoes the normalised operation plus the result, whether it changed, and the code-point length of the result. "
                    + "Every call is journalled as TEXT_TRANSFORM and can be replayed.")
    public ApiResponse<TextTransformResponse> transform(@Valid @RequestBody TextTransformRequest request) {
        TextTransformResponse result = auditedOperations.transform(request);
        return ApiResponse.success(result, "Text transformed successfully");
    }

    @PostMapping("/replace")
    @Operation(summary = "Replace text occurrences",
            description = "Replaces every (or, with all=false, the first) occurrence of a literal or regular-expression pattern. "
                    + "Literal mode quotes the pattern and the replacement; regex mode supports $n group references. "
                    + "The response reports the result, the number of replacements, whether it changed and the code-point length. "
                    + "Every call is journalled as TEXT_REPLACE and can be replayed.")
    public ApiResponse<TextReplaceResponse> replace(@Valid @RequestBody TextReplaceRequest request) {
        return ApiResponse.success(auditedOperations.replace(request), "Text replaced successfully");
    }

    @PostMapping("/html-entities")
    @Operation(summary = "Encode or decode HTML entities",
            description = "ENCODE escapes & first, then <, >, \" and ' to &amp; &lt; &gt; &quot; &#39;. "
                    + "DECODE resolves those five named entities plus decimal (&#NN;) and hexadecimal (&#xHH;) "
                    + "numeric references; unknown or malformed references are preserved verbatim. "
                    + "The response echoes the action plus the result, whether it changed and the code-point length. "
                    + "Every call is journalled as TEXT_HTML_ENTITIES and can be replayed.")
    public ApiResponse<TextHtmlEntitiesResponse> htmlEntities(@Valid @RequestBody TextHtmlEntitiesRequest request) {
        return ApiResponse.success(auditedOperations.htmlEntities(request), "HTML entities processed successfully");
    }

    @PostMapping("/stats")
    @Operation(summary = "Compute text statistics",
            description = "Counts Unicode code points (with and without whitespace), words (letter/digit runs), lines and sentences "
                    + "for the supplied text. Every call is journalled as TEXT_STATS and can be replayed.")
    public ApiResponse<TextStatsResponse> stats(@Valid @RequestBody TextStatsRequest request) {
        TextStatsResponse result = auditedOperations.stats(request);
        return ApiResponse.success(result, "Text statistics computed successfully");
    }

    @PostMapping("/truncate")
    @Operation(summary = "Truncate text to a maximum length",
            description = "Bounds the text to at most maxLength code points. When the text is longer, the result is the "
                    + "truncated prefix plus the ellipsis and is guaranteed never to exceed maxLength code points nor split a "
                    + "surrogate pair. A null ellipsis defaults to '…'; an empty ellipsis uses no marker. wordBoundary=true "
                    + "cuts at the last whitespace before the limit and trims trailing space (falling back to a hard cut when "
                    + "no word boundary fits); wordBoundary=false (the default) hard-cuts. Every call is journalled as "
                    + "TEXT_TRUNCATE and can be replayed.")
    public ApiResponse<TextTruncateResponse> truncate(@Valid @RequestBody TextTruncateRequest request) {
        return ApiResponse.success(auditedOperations.truncate(request), "Text truncated successfully");
    }

    @PostMapping("/word-frequency")
    @Operation(summary = "Compute word frequency",
            description = "Counts tokens with the /stats tokenizer. When caseSensitive is false (the default), tokens and "
                    + "ignoreWords are folded with Locale.ROOT; ignored words are excluded before counting, and the response "
                    + "returns totalWords, distinctWords and a bounded topWords list ordered by count descending, then word "
                    + "ascending, so a journalled result always replays exactly. Every call is journalled as "
                    + "TEXT_WORD_FREQUENCY and can be replayed.")
    public ApiResponse<TextWordFrequencyResponse> wordFrequency(@Valid @RequestBody TextWordFrequencyRequest request) {
        return ApiResponse.success(auditedOperations.wordFrequency(request), "Word frequency computed successfully");
    }
}
