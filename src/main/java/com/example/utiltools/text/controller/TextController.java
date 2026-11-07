package com.example.utiltools.text.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.text.dto.TextReplaceRequest;
import com.example.utiltools.text.dto.TextReplaceResponse;
import com.example.utiltools.text.dto.TextStatsRequest;
import com.example.utiltools.text.dto.TextStatsResponse;
import com.example.utiltools.text.dto.TextTransformRequest;
import com.example.utiltools.text.dto.TextTransformResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/text")
@Tag(name = "Text Transformation & Statistics Utility", description = "Endpoints for deterministic case/format conversion, find-and-replace and text statistics")
public class TextController {

    private final AuditedOperations auditedOperations;

    public TextController(AuditedOperations auditedOperations) {
        this.auditedOperations = auditedOperations;
    }

    @PostMapping("/transform")
    @Operation(summary = "Transform text case and format",
            description = "Applies one deterministic operation to the supplied text: UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB or SLUG. "
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

    @PostMapping("/stats")
    @Operation(summary = "Compute text statistics",
            description = "Counts Unicode code points (with and without whitespace), words (letter/digit runs), lines and sentences "
                    + "for the supplied text. Every call is journalled as TEXT_STATS and can be replayed.")
    public ApiResponse<TextStatsResponse> stats(@Valid @RequestBody TextStatsRequest request) {
        TextStatsResponse result = auditedOperations.stats(request);
        return ApiResponse.success(result, "Text statistics computed successfully");
    }
}
