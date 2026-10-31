package com.example.utiltools.json.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonValidateRequest;
import com.example.utiltools.json.dto.JsonValidateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/json")
@Tag(name = "JSON & Data Processor Utility", description = "Endpoints for formatting, minifying, validating, and querying JSON structures")
public class JsonController {

    private final AuditedOperations auditedOperations;

    public JsonController(AuditedOperations auditedOperations) {
        this.auditedOperations = auditedOperations;
    }

    @PostMapping("/format")
    @Operation(summary = "Pretty print or minify JSON", description = "Formats JSON with indentation or minifies it into a compact single-line string")
    public ApiResponse<JsonFormatResponse> format(@Valid @RequestBody JsonFormatRequest request) {
        JsonFormatResponse result = auditedOperations.format(request);
        return ApiResponse.success(result, "JSON formatted successfully");
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate JSON syntax", description = "Checks whether JSON is syntactically valid and returns node type or precise error location")
    public ApiResponse<JsonValidateResponse> validate(@Valid @RequestBody JsonValidateRequest request) {
        JsonValidateResponse result = auditedOperations.validate(request);
        return ApiResponse.success(result, result.valid() ? "JSON is valid" : "JSON syntax is invalid");
    }

    @PostMapping("/query")
    @Operation(summary = "Query JSON using JSONPath", description = "Extracts nodes, objects, or arrays matching a standard JSONPath expression")
    public ApiResponse<JsonQueryResponse> query(@Valid @RequestBody JsonQueryRequest request) {
        JsonQueryResponse result = auditedOperations.query(request);
        return ApiResponse.success(result, "JSONPath executed successfully");
    }
}
