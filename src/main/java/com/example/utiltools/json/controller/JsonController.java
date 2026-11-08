package com.example.utiltools.json.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.json.dto.JsonCsvRequest;
import com.example.utiltools.json.dto.JsonCsvResponse;
import com.example.utiltools.json.dto.JsonDiffRequest;
import com.example.utiltools.json.dto.JsonDiffResponse;
import com.example.utiltools.json.dto.JsonFlattenRequest;
import com.example.utiltools.json.dto.JsonFlattenResponse;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonFromCsvRequest;
import com.example.utiltools.json.dto.JsonFromCsvResponse;
import com.example.utiltools.json.dto.JsonFromNdjsonRequest;
import com.example.utiltools.json.dto.JsonFromNdjsonResponse;
import com.example.utiltools.json.dto.JsonMergePatchRequest;
import com.example.utiltools.json.dto.JsonMergePatchResponse;
import com.example.utiltools.json.dto.JsonPatchRequest;
import com.example.utiltools.json.dto.JsonPatchResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.dto.JsonToCsvRequest;
import com.example.utiltools.json.dto.JsonToCsvResponse;
import com.example.utiltools.json.dto.JsonToNdjsonRequest;
import com.example.utiltools.json.dto.JsonToNdjsonResponse;
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
@Tag(name = "JSON & Data Processor Utility", description = "Endpoints for formatting, minifying, validating, querying, flattening, unflattening, atomically patching JSON structures, converting between JSON arrays and CSV/TSV tables, and converting between JSON arrays and NDJSON / JSON Lines, and diffing two JSON documents")
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

    @PostMapping("/to-csv")
    @Operation(summary = "Convert a JSON array of flat objects to CSV/TSV",
            description = "Exports a JSON array of flat objects as RFC 4180 text. The header is the union of keys in "
                    + "first-seen order, records are separated by CRLF, and any field carrying the delimiter, a double quote, "
                    + "CR or LF is quoted with doubled quotes. null or absent fields become empty cells; nested objects or "
                    + "arrays, a non-array document and more than 10000 rows or columns are rejected with 400 BAD_REQUEST.")
    public ApiResponse<JsonToCsvResponse> toCsv(@Valid @RequestBody JsonToCsvRequest request) {
        JsonToCsvResponse result = auditedOperations.toCsv(request);
        return ApiResponse.success(result, "JSON converted to CSV successfully");
    }

    @PostMapping("/from-csv")
    @Operation(summary = "Convert CSV/TSV to a JSON array",
            description = "Imports RFC 4180 CSV or TSV into a JSON array of string-valued objects. Quoted fields may carry "
                    + "the delimiter, LF, CRLF or CR newlines and doubled quotes; a UTF-8 BOM is tolerated. hasHeader=true "
                    + "(the default) uses the first record for property names, mapping blank names to columnN, while "
                    + "hasHeader=false uses column1..N. Duplicate header names, an unterminated quote, malformed text and "
                    + "more than 10000 rows or columns are rejected with 400 BAD_REQUEST.")
    public ApiResponse<JsonFromCsvResponse> fromCsv(@Valid @RequestBody JsonFromCsvRequest request) {
        JsonFromCsvResponse result = auditedOperations.fromCsv(request);
        return ApiResponse.success(result, "CSV converted to JSON successfully");
    }

    @PostMapping("/csv-to-json")
    @Operation(summary = "Convert CSV to a JSON array",
            description = "Imports RFC 4180 CSV into a JSON array of string-valued objects. csvContent is the CSV text; "
                    + "delimiter is optional and defaults to comma (a single character, '\\t' or a literal tab for TSV). "
                    + "The first record is the header and supplies the object keys, blank header names map to columnN; "
                    + "headerMapping optionally renames a header to a different JSON property name and must stay unique. "
                    + "Quoted fields may carry the delimiter, LF, CRLF or CR, doubled quotes and a UTF-8 BOM are tolerated. "
                    + "Duplicate header names, an unterminated quote, malformed text and more than 10000 rows or columns "
                    + "are rejected with 400 BAD_REQUEST.")
    public ApiResponse<JsonCsvResponse> csvToJson(@Valid @RequestBody JsonCsvRequest request) {
        JsonCsvResponse result = auditedOperations.csvToJson(request);
        return ApiResponse.success(result, "CSV converted to JSON successfully");
    }

    @PostMapping("/to-ndjson")
    @Operation(summary = "Convert a JSON array to NDJSON / JSON Lines",
            description = "Exports a JSON array as Newline Delimited JSON: one compact JSON value per line, joined by "
                    + "a single newline with no trailing newline. lineCount reports the record count and sizeBytes the "
                    + "UTF-8 byte size. An empty array returns an empty string with lineCount 0; malformed JSON or a "
                    + "non-array document is rejected with 400 BAD_REQUEST.")
    public ApiResponse<JsonToNdjsonResponse> toNdjson(@Valid @RequestBody JsonToNdjsonRequest request) {
        JsonToNdjsonResponse result = auditedOperations.toNdjson(request);
        return ApiResponse.success(result, "JSON converted to NDJSON successfully");
    }

    @PostMapping("/from-ndjson")
    @Operation(summary = "Convert NDJSON / JSON Lines to a JSON array",
            description = "Imports Newline Delimited JSON: each non-blank physical line is parsed as one independent "
                    + "JSON value (objects, arrays or scalars) in input order; blank lines and CRLF line endings are "
                    + "tolerated. An empty document yields an empty array, a malformed line is rejected with 400 "
                    + "BAD_REQUEST naming its 1-based physical line number, and more than 10000 records is rejected.")
    public ApiResponse<JsonFromNdjsonResponse> fromNdjson(@Valid @RequestBody JsonFromNdjsonRequest request) {
        JsonFromNdjsonResponse result = auditedOperations.fromNdjson(request);
        return ApiResponse.success(result, "NDJSON converted to JSON successfully");
    }

    @PostMapping("/diff")
    @Operation(summary = "Diff two JSON documents",
            description = "Compares jsonA with jsonB and reports added, removed and changed paths. Objects are "
                    + "compared member by member in first-seen order, arrays position by position ([0], [1], ...), "
                    + "and a scalar or type change is reported at its own path; a whole-root mismatch is reported at "
                    + "'$'. changed maps each path to a JsonDiffDetail whose changeType is ADDED, REMOVED or CHANGED, "
                    + "carrying compact JSON oldValue/newValue (null on the missing side), and summary reads '<n> "
                    + "added, <n> removed, <n> changed' or 'No differences found'. Malformed jsonA or jsonB returns "
                    + "400 BAD_REQUEST naming that field, and a document nested deeper than 64 levels or producing "
                    + "more than 10000 changes is rejected.")
    public ApiResponse<JsonDiffResponse> diff(@Valid @RequestBody JsonDiffRequest request) {
        JsonDiffResponse result = auditedOperations.diff(request);
        return ApiResponse.success(result, "JSON documents diffed successfully");
    }
}
