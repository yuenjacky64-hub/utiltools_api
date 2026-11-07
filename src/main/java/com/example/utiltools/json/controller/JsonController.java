package com.example.utiltools.json.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.json.dto.JsonFlattenRequest;
import com.example.utiltools.json.dto.JsonFlattenResponse;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonMergePatchRequest;
import com.example.utiltools.json.dto.JsonMergePatchResponse;
import com.example.utiltools.json.dto.JsonPatchRequest;
import com.example.utiltools.json.dto.JsonPatchResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonUnflattenRequest;
import com.example.utiltools.json.dto.JsonUnflattenResponse;
import com.example.utiltools.json.dto.JsonValidateRequest;
import com.example.utiltools.json.dto.JsonValidateResponse;
import com.example.utiltools.json.service.JsonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/json")
@Tag(name = "JSON & Data Processor Utility", description = "Endpoints for formatting, minifying, validating, querying, flattening, unflattening, and atomically patching JSON structures")
public class JsonController {

    private final AuditedOperations auditedOperations;
    private final JsonService jsonService;

    public JsonController(AuditedOperations auditedOperations, JsonService jsonService) {
        this.auditedOperations = auditedOperations;
        this.jsonService = jsonService;
    }

    @PostMapping("/format")
    @Operation(summary = "Pretty print or minify JSON", description = "Formats JSON with indentation or minifies it into a compact single-line string. "
            + "sortKeys=true recursively orders object field names in ascending String order for both "
            + "minified and pretty output; array element order and scalar values are preserved. "
            + "sortKeys is optional and defaults to false.")
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

    @PostMapping("/flatten")
    @Operation(summary = "Flatten JSON into dot-path keys", description = "Collapses an object tree into separator-joined key/value pairs while preserving insertion order. "
            + "Arrays and empty objects stay untouched leaf values, keyCount counts leaves and maxDepth counts path segments; "
            + "a key that already contains the separator is rejected so the result can always be unflattened back.")
    public ApiResponse<JsonFlattenResponse> flatten(@Valid @RequestBody JsonFlattenRequest request) {
        JsonFlattenResponse result = auditedOperations.flatten(request);
        return ApiResponse.success(result, "JSON flattened successfully");
    }

    @PostMapping("/unflatten")
    @Operation(summary = "Unflatten dot-path keys into JSON", description = "Rebuilds a nested object from separator-joined keys. "
            + "Blank or overlong separators, empty path segments, non-object roots, conflicting paths such as 'a' together with 'a.b', "
            + "depth above 64 and more than 10000 keys are rejected with 400 BAD_REQUEST.")
    public ApiResponse<JsonUnflattenResponse> unflatten(@Valid @RequestBody JsonUnflattenRequest request) {
        JsonUnflattenResponse result = auditedOperations.unflatten(request);
        return ApiResponse.success(result, "JSON unflattened successfully");
    }

    @PostMapping("/merge-patch")
    @Operation(summary = "Apply an RFC 7396 JSON merge patch",
            description = "Applies a declarative merge-patch document to the target JSON: a null member deletes that key "
                    + "(counted in removedKeys), objects merge recursively, and arrays or scalars replace the target value wholesale. "
                    + "A non-object patch such as 7, \"abc\" or [1,2] replaces the whole target. Both documents are parsed but never mutated; "
                    + "malformed JSON returns 400 BAD_REQUEST and a patch nested beyond 64 object levels is rejected.")
    public ApiResponse<JsonMergePatchResponse> mergePatch(@Valid @RequestBody JsonMergePatchRequest request) {
        JsonMergePatchResponse result = auditedOperations.mergePatch(request);
        return ApiResponse.success(result, "JSON merge patch applied successfully");
    }

    @PostMapping("/patch")
    @Operation(summary = "Apply an atomic JSON Patch", description = "Applies an ordered RFC 6902-style operation list (add, remove, replace, test, move, copy) to a deep copy of the document. "
            + "On the first failure every previously applied operation is rolled back in reverse order and the request fails with 422 PATCH_ROLLBACK carrying the full audit trail. "
            + "dryRun=true validates the whole operation list and returns resultJson=null.")
    public ApiResponse<JsonPatchResponse> patch(@Valid @RequestBody JsonPatchRequest request) {
        JsonPatchResponse result = jsonService.patch(request);
        String message = result.dryRun()
                ? "JSON patch validated (dry run)"
                : "JSON patch applied successfully";
        return ApiResponse.success(result, message);
    }
}
