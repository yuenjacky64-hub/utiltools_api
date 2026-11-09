package com.example.utiltools.json;

import com.example.utiltools.json.dto.JsonCsvRequest;
import com.example.utiltools.json.dto.JsonCsvResponse;
import com.example.utiltools.json.dto.JsonDiffDetail;
import com.example.utiltools.json.dto.JsonDiffRequest;
import com.example.utiltools.json.dto.JsonDiffResponse;
import com.example.utiltools.json.dto.JsonFlattenRequest;
import com.example.utiltools.json.dto.JsonFromCsvRequest;
import com.example.utiltools.json.dto.JsonFromCsvResponse;
import com.example.utiltools.json.dto.JsonFromNdjsonRequest;
import com.example.utiltools.json.dto.JsonFlattenResponse;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonMergePatchRequest;
import com.example.utiltools.json.dto.JsonMergePatchResponse;
import com.example.utiltools.json.dto.JsonPatchOperation;
import com.example.utiltools.json.dto.JsonPatchResponse;
import com.example.utiltools.json.dto.JsonPatchRequest;
import com.example.utiltools.json.dto.JsonPointerRequest;
import com.example.utiltools.json.dto.JsonPointerResponse;
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
import com.example.utiltools.json.exception.JsonPatchRollbackException;
import com.example.utiltools.json.service.JsonService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonServiceTest {

    private JsonService jsonService;

    @BeforeEach
    void setUp() {
        jsonService = new JsonService(new ObjectMapper());
    }

    @Test
    @DisplayName("Should pretty print JSON correctly")
    void shouldPrettyPrintJson() {
        String compact = "{\"name\":\"Alice\",\"age\":30}";
        JsonFormatRequest request = new JsonFormatRequest(compact, false, 2, null);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).contains("\n");
        assertThat(response.formattedJson()).contains("\"name\" : \"Alice\"");
        assertThat(response.sizeBytes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should minify formatted JSON correctly")
    void shouldMinifyJson() {
        String formatted = """
                {
                   "name": "Alice",
                   "age": 30
                }
                """;
        JsonFormatRequest request = new JsonFormatRequest(formatted, true, null, null);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).isEqualTo("{\"name\":\"Alice\",\"age\":30}");
    }

    @Test
    @DisplayName("Should indent nested JSON with the supplied width of four spaces")
    void shouldIndentNestedOutputWithWidthFour() {
        JsonFormatRequest request = new JsonFormatRequest("{\"a\":{\"b\":1}}", false, 4, null);
        JsonFormatResponse response = jsonService.format(request);

        String[] lines = response.formattedJson().split("\\R");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).isEqualTo("{");
        assertThat(lines[1]).isEqualTo("    \"a\" : {");
        assertThat(lines[2]).isEqualTo("        \"b\" : 1");
        assertThat(lines[3]).isEqualTo("    }");
        assertThat(lines[4]).isEqualTo("}");
    }

    @Test
    @DisplayName("Should default to two spaces when indent is omitted")
    void shouldDefaultToTwoSpacesWhenIndentOmitted() throws JsonProcessingException {
        String json = "{\"a\":{\"b\":1}}";
        JsonFormatRequest request = new JsonFormatRequest(json, false, null, null);
        JsonFormatResponse response = jsonService.format(request);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        assertThat(response.formattedJson())
                .isEqualTo(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    @Test
    @DisplayName("Should ignore indent when minify is true")
    void shouldIgnoreIndentWhenMinifyTrue() {
        JsonFormatRequest request = new JsonFormatRequest("{\"a\":{\"b\":1}}", true, 8, null);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).isEqualTo("{\"a\":{\"b\":1}}");
        assertThat(response.formattedJson()).doesNotContain("\n");
    }

    @Test
    @DisplayName("Should compute sizeBytes from the final formatted output")
    void shouldComputeSizeBytesFromFormattedOutput() {
        String json = "{\"a\":{\"b\":1}}";
        JsonFormatResponse indented = jsonService.format(new JsonFormatRequest(json, false, 4, null));
        JsonFormatResponse minified = jsonService.format(new JsonFormatRequest(json, true, null, null));

        assertThat(indented.sizeBytes())
                .isEqualTo(indented.formattedJson().getBytes(StandardCharsets.UTF_8).length);
        assertThat(indented.sizeBytes()).isNotEqualTo(minified.sizeBytes());
    }

    @Test
    @DisplayName("Should sort keys recursively when minifying")
    void shouldSortKeysRecursivelyWhenMinifying() {
        String json = "{\"z\":1,\"a\":{\"d\":4,\"b\":2},\"list\":[{\"y\":1,\"x\":2}]}";
        JsonFormatRequest request = new JsonFormatRequest(json, true, null, true);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson())
                .isEqualTo("{\"a\":{\"b\":2,\"d\":4},\"list\":[{\"x\":2,\"y\":1}],\"z\":1}");
        assertThat(response.sizeBytes())
                .isEqualTo(response.formattedJson().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Should preserve array element order and scalar values when sorting keys")
    void shouldPreserveArrayElementOrderAndScalarValuesWhenSorting() {
        String json = "{\"arr\":[3,{\"b\":1,\"a\":2},\"text\",true,null,2,1]}";
        JsonFormatRequest request = new JsonFormatRequest(json, true, null, true);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson())
                .isEqualTo("{\"arr\":[3,{\"a\":2,\"b\":1},\"text\",true,null,2,1]}");
    }

    @Test
    @DisplayName("Should preserve input order when sortKeys is null")
    void shouldPreserveInputOrderWhenSortKeysIsNull() {
        String json = "{\"z\":1,\"a\":{\"d\":4,\"b\":2},\"list\":[{\"y\":1,\"x\":2}]}";
        JsonFormatRequest request = new JsonFormatRequest(json, true, null, null);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson())
                .isEqualTo("{\"z\":1,\"a\":{\"d\":4,\"b\":2},\"list\":[{\"y\":1,\"x\":2}]}");
    }

    @Test
    @DisplayName("Should preserve input order when sortKeys is false")
    void shouldPreserveInputOrderWhenSortKeysIsFalse() {
        String json = "{\"z\":1,\"a\":{\"d\":4,\"b\":2},\"list\":[{\"y\":1,\"x\":2}]}";
        JsonFormatRequest request = new JsonFormatRequest(json, true, null, false);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson())
                .isEqualTo("{\"z\":1,\"a\":{\"d\":4,\"b\":2},\"list\":[{\"y\":1,\"x\":2}]}");
    }

    @Test
    @DisplayName("Should keep indent controlling pretty-printed output when sorting keys")
    void shouldIndentSortedOutput() {
        String json = "{\"z\":1,\"a\":{\"d\":4,\"b\":2}}";
        JsonFormatResponse twoSpaces = jsonService.format(new JsonFormatRequest(json, false, null, true));
        JsonFormatResponse fourSpaces = jsonService.format(new JsonFormatRequest(json, false, 4, true));

        String[] twoLines = twoSpaces.formattedJson().split("\\R");
        assertThat(twoLines).hasSize(7);
        assertThat(twoLines[0]).isEqualTo("{");
        assertThat(twoLines[1]).isEqualTo("  \"a\" : {");
        assertThat(twoLines[2]).isEqualTo("    \"b\" : 2,");
        assertThat(twoLines[3]).isEqualTo("    \"d\" : 4");
        assertThat(twoLines[4]).isEqualTo("  },");
        assertThat(twoLines[5]).isEqualTo("  \"z\" : 1");
        assertThat(twoLines[6]).isEqualTo("}");

        String[] fourLines = fourSpaces.formattedJson().split("\\R");
        assertThat(fourLines[1]).isEqualTo("    \"a\" : {");
        assertThat(fourLines[2]).isEqualTo("        \"b\" : 2,");
        assertThat(fourLines[3]).isEqualTo("        \"d\" : 4");
    }

    @Test
    @DisplayName("Should compute sizeBytes from the sorted output")
    void shouldComputeSizeBytesFromSortedOutput() {
        String json = "{\"z\":1,\"a\":{\"d\":4,\"b\":2}}";
        JsonFormatResponse response = jsonService.format(new JsonFormatRequest(json, true, null, true));

        assertThat(response.formattedJson()).isEqualTo("{\"a\":{\"b\":2,\"d\":4},\"z\":1}");
        assertThat(response.sizeBytes())
                .isEqualTo(response.formattedJson().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Should validate valid JSON")
    void shouldValidateValidJson() {
        JsonValidateRequest request = new JsonValidateRequest("{\"status\": \"active\", \"code\": 200}");
        JsonValidateResponse response = jsonService.validate(request);

        assertThat(response.valid()).isTrue();
        assertThat(response.error()).isNull();
        assertThat(response.type()).isEqualTo("OBJECT");
    }

    @Test
    @DisplayName("Should detect invalid JSON and report error details")
    void shouldDetectInvalidJson() {
        JsonValidateRequest request = new JsonValidateRequest("{\"unclosed\": [1, 2, }");
        JsonValidateResponse response = jsonService.validate(request);

        assertThat(response.valid()).isFalse();
        assertThat(response.error()).isNotBlank();
        assertThat(response.line()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Should execute JSONPath query accurately")
    @SuppressWarnings("unchecked")
    void shouldExecuteJsonPathQuery() {
        String json = """
                {
                  "store": {
                    "books": [
                      {"title": "Java 21 in Action", "price": 45.0},
                      {"title": "Spring Boot 4 Guide", "price": 50.0}
                    ]
                  }
                }
                """;
        JsonQueryRequest request = new JsonQueryRequest(json, "$.store.books[*].title");
        JsonQueryResponse response = jsonService.query(request);

        assertThat(response.jsonPath()).isEqualTo("$.store.books[*].title");
        assertThat(response.result()).isInstanceOf(List.class);
        List<String> titles = (List<String>) response.result();
        assertThat(titles).containsExactly("Java 21 in Action", "Spring Boot 4 Guide");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException on malformed JSONPath")
    void shouldThrowOnMalformedJsonPath() {
        JsonQueryRequest request = new JsonQueryRequest("{\"a\": 1}", "invalid$$path[");
        assertThatThrownBy(() -> jsonService.query(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSONPath syntax");
    }

    // ---------------------------------------------------------------- JSON Patch

    private static JsonPatchOperation op(String op, String path, String from, String rawValue) {
        JsonNode value = null;
        if (rawValue != null) {
            try {
                value = new ObjectMapper().readTree(rawValue);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return new JsonPatchOperation(null, op, path, from, value, null, null, null, null);
    }

    @Test
    @DisplayName("Should apply a six-operation patch and audit indices 0..5")
    void shouldApplySixOperationPatch() throws Exception {
        String json = """
                {"name":"Alice","age":30,"tags":["a","b"],"temp":{"x":1},"spare":"keep"}
                """;
        JsonPatchRequest request = new JsonPatchRequest(json, List.of(
                op("replace", "/name", null, "\"Bob\""),
                op("add", "/email", null, "\"bob@example.com\""),
                op("remove", "/spare", null, null),
                op("test", "/age", null, "30"),
                op("move", "/temp/y", "/temp/x", null),
                op("copy", "/tags/-", "/tags/0", null)
        ), false);

        JsonPatchResponse response = jsonService.patch(request);

        JsonNode result = response.resultJson();
        assertThat(result.get("name").asText()).isEqualTo("Bob");
        assertThat(result.get("email").asText()).isEqualTo("bob@example.com");
        assertThat(result.has("spare")).isFalse();
        assertThat(result.get("temp").get("y").asInt()).isEqualTo(1);
        assertThat(result.get("temp").has("x")).isFalse();
        assertThat(result.get("tags")).hasSize(3);
        assertThat(result.get("tags").get(2).asText()).isEqualTo("a");
        assertThat(response.operationsApplied()).isEqualTo(6);
        assertThat(response.operationsRolledBack()).isZero();
        assertThat(response.dryRun()).isFalse();
        assertThat(response.audit()).hasSize(6);
        for (int i = 0; i < 6; i++) {
            assertThat(response.audit().get(i).index()).isEqualTo(i);
            assertThat(response.audit().get(i).status()).isEqualTo(JsonPatchOperation.APPLIED);
        }
    }

    @Test
    @DisplayName("Should roll back a failed test operation to a byte-identical original")
    void shouldRollBackToByteIdenticalOriginalOnFailingTestOperation() throws Exception {
        String json = "{\"name\":\"Alice\",\"age\":30,\"tags\":[\"a\"]}";
        String originalBytes = new ObjectMapper().writeValueAsString(new ObjectMapper().readTree(json));
        JsonPatchRequest request = new JsonPatchRequest(json, List.of(
                op("replace", "/name", null, "\"Bob\""),
                op("add", "/nickname", null, "\"Al\""),
                op("remove", "/tags/0", null, null),
                op("test", "/age", null, "31")
        ), false);

        assertThatThrownBy(() -> jsonService.patch(request))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("operation 3")
                .hasMessageContaining("/age")
                .satisfies(ex -> {
                    JsonPatchRollbackException rollback = (JsonPatchRollbackException) ex;
                    assertThat(rollback.failedIndex()).isEqualTo(3);
                    assertThat(rollback.operationsRolledBack()).isEqualTo(3);
                    assertThat(rollback.audit()).hasSize(4);
                    assertThat(rollback.audit().get(3).status()).isEqualTo(JsonPatchOperation.FAILED);
                    assertThat(rollback.audit().subList(0, 3))
                            .allSatisfy(entry -> assertThat(entry.status())
                                    .isEqualTo(JsonPatchOperation.ROLLED_BACK));
                });

        // The caller's text was never touched and a fresh patch of the same text still sees the original doc.
        JsonPatchResponse unchanged = jsonService.patch(new JsonPatchRequest(json, List.of(
                op("test", "/name", null, "\"Alice\"")), false));
        assertThat(unchanged.resultJson()).isEqualTo(new ObjectMapper().readTree(json));
        assertThat(new ObjectMapper().writeValueAsString(unchanged.resultJson())).isEqualTo(originalBytes);
    }

    @Test
    @DisplayName("Should fail on a missing path and roll back earlier operations")
    void shouldFailOnMissingPath() {
        JsonPatchRequest request = new JsonPatchRequest("{\"name\":\"Alice\"}", List.of(
                op("replace", "/name", null, "\"Bob\""),
                op("remove", "/missing", null, null)
        ), false);

        assertThatThrownBy(() -> jsonService.patch(request))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("operation 1")
                .hasMessageContaining("/missing");

        JsonPatchRollbackException rollback = (JsonPatchRollbackException)
                org.assertj.core.api.Assertions.catchThrowable(() -> jsonService.patch(request));
        assertThat(rollback.audit().get(0).status()).isEqualTo(JsonPatchOperation.ROLLED_BACK);
        assertThat(rollback.audit().get(1).status()).isEqualTo(JsonPatchOperation.FAILED);
        assertThat(rollback.audit().get(1).error()).contains("Missing path");
    }

    @Test
    @DisplayName("Should reject a malformed JSON Pointer")
    void shouldRejectMalformedPointer() {
        JsonPatchRequest noSlash = new JsonPatchRequest("{\"a\":1}",
                List.of(op("add", "a", null, "2")), false);
        assertThatThrownBy(() -> jsonService.patch(noSlash))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("Malformed JSON Pointer");

        JsonPatchRequest badEscape = new JsonPatchRequest("{\"a\":1}",
                List.of(op("add", "/a~2", null, "2")), false);
        assertThatThrownBy(() -> jsonService.patch(badEscape))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("Malformed JSON Pointer");
    }

    @Test
    @DisplayName("Should append to an array with the '-' token")
    void shouldAppendToArrayWithDashToken() {
        JsonPatchRequest append = new JsonPatchRequest("{\"a\":[1,2]}",
                List.of(op("add", "/a/-", null, "3")), false);
        assertThat(jsonService.patch(append).resultJson().get("a")).hasSize(3);

        // '-' is only an append marker for add; elsewhere it is an invalid array index.
        assertThatThrownBy(() -> jsonService.patch(new JsonPatchRequest("{\"a\":[1,2]}",
                List.of(op("replace", "/a/-", null, "9")), false)))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("Invalid array index: -");
    }

    @Test
    @DisplayName("Should unescape ~0/~1 in pointers and reject bad ~ escapes")
    void shouldRejectMalformedPointerEscapes() {
        JsonPatchRequest unescape = new JsonPatchRequest("{\"a/b\":1,\"c~d\":2}", List.of(
                op("replace", "/a~1b", null, "10"),
                op("replace", "/c~0d", null, "20")
        ), false);
        JsonNode result = jsonService.patch(unescape).resultJson();
        assertThat(result.get("a/b").asInt()).isEqualTo(10);
        assertThat(result.get("c~d").asInt()).isEqualTo(20);

        // "~01" unescapes to "~1" (RFC 6901 order: ~1 first, then ~0).
        assertThat(jsonService.patch(new JsonPatchRequest("{\"~1\":7}",
                List.of(op("replace", "/~01", null, "8")), false)).resultJson().get("~1").asInt()).isEqualTo(8);

        assertThatThrownBy(() -> jsonService.patch(new JsonPatchRequest("{\"a\":1}",
                List.of(op("add", "/a~2b", null, "2")), false)))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("Malformed JSON Pointer");
    }

    @Test
    @DisplayName("Should validate only when dryRun is true and leave the input untouched")
    void shouldValidateWithoutMutatingOnDryRun() {
        String json = "{\"name\":\"Alice\",\"tags\":[\"a\"]}";
        JsonPatchRequest dryRun = new JsonPatchRequest(json, List.of(
                op("replace", "/name", null, "\"Bob\""),
                op("add", "/tags/-", null, "\"b\"")
        ), true);

        JsonPatchResponse response = jsonService.patch(dryRun);

        assertThat(response.dryRun()).isTrue();
        assertThat(response.resultJson()).isNull();
        assertThat(response.operationsApplied()).isZero();
        assertThat(response.audit()).allSatisfy(entry ->
                assertThat(entry.status()).isEqualTo(JsonPatchOperation.VALIDATED));

        // Same input string, real run: proves the dry run leaked no mutation.
        JsonPatchResponse real = jsonService.patch(new JsonPatchRequest(json, List.of(
                op("replace", "/name", null, "\"Bob\"")), false));
        assertThat(real.resultJson().get("name").asText()).isEqualTo("Bob");
        assertThat(real.resultJson().get("tags")).hasSize(1);
    }

    @Test
    @DisplayName("Should record FAILED and ROLLED_BACK audit entries on a failed dry run")
    void shouldRecordFailureOnDryRun() {
        JsonPatchRequest request = new JsonPatchRequest("{\"name\":\"Alice\"}", List.of(
                op("replace", "/name", null, "\"Bob\""),
                op("remove", "/missing", null, null)
        ), true);

        assertThatThrownBy(() -> jsonService.patch(request))
                .isInstanceOf(JsonPatchRollbackException.class)
                .satisfies(ex -> {
                    JsonPatchRollbackException rollback = (JsonPatchRollbackException) ex;
                    assertThat(rollback.audit().get(0).status())
                            .isEqualTo(JsonPatchOperation.ROLLED_BACK);
                    assertThat(rollback.audit().get(1).status())
                            .isEqualTo(JsonPatchOperation.FAILED);
                });
    }

    @Test
    @DisplayName("Should never mutate the caller-supplied input document or value nodes")
    void shouldNotMutateInputJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String inputJson = "{\"a\":1,\"nested\":{\"list\":[1,2]}}";
        JsonNode parsedInput = mapper.readTree(inputJson);
        String inputBefore = mapper.writeValueAsString(parsedInput);

        JsonNode callerValue = mapper.readTree("{\"nested\":[1,2]}");
        String callerValueBefore = mapper.writeValueAsString(callerValue);
        // Caller-supplied audit fields must be ignored and re-derived by the service.
        JsonPatchOperation operation = new JsonPatchOperation(99, "add", "/copy", null,
                callerValue, "APPLIED", mapper.readTree("\"forged\""), mapper.readTree("\"forged\""),
                "forged error");
        JsonPatchRequest request = new JsonPatchRequest(inputJson, List.of(operation), false);

        JsonPatchResponse response = jsonService.patch(request);

        assertThat(response.resultJson().get("copy").get("nested")).hasSize(2);
        assertThat(response.audit().get(0).index()).isZero();
        assertThat(response.audit().get(0).status()).isEqualTo(JsonPatchOperation.APPLIED);
        assertThat(response.audit().get(0).error()).isNull();
        assertThat(response.audit().get(0).beforeValue()).isNull();

        // Mutating the result must not reach back into the caller's node or the parsed input.
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.resultJson().get("copy"))
                .put("extra", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.resultJson()).put("touched", true);
        assertThat(callerValue.has("extra")).isFalse();
        assertThat(mapper.writeValueAsString(callerValue)).isEqualTo(callerValueBefore);
        assertThat(mapper.writeValueAsString(parsedInput)).isEqualTo(inputBefore);

        // `move` into its own descendant is refused through the rollback path.
        assertThatThrownBy(() -> jsonService.patch(new JsonPatchRequest(inputJson,
                List.of(op("move", "/nested/list", "/nested", null)), false)))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("Cannot move a node into its own descendant");
    }

    @Test
    @DisplayName("Should reject unknown operations and index bounds violations")
    void shouldRejectUnknownOperation() {
        assertThatThrownBy(() -> jsonService.patch(new JsonPatchRequest("{\"a\":1}",
                List.of(op("merge", "/a", null, "2")), false)))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("Unknown operation: merge");

        assertThatThrownBy(() -> jsonService.patch(new JsonPatchRequest("{\"a\":[1,2]}",
                List.of(op("replace", "/a/5", null, "9")), false)))
                .isInstanceOf(JsonPatchRollbackException.class)
                .hasMessageContaining("Array index out of bounds");
    }

    @Test
    @DisplayName("Should keep the @NotEmpty rule alongside the 100-operation cap")
    void shouldPreserveNotEmptyConstraintAlongsideOperationCap() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new JsonPatchRequest("{\"a\":1}", List.of(), false)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("operations list is required");
            assertThat(validator.validate(new JsonPatchRequest("{\"a\":1}", null, false)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("operations list is required");
            assertThat(validator.validate(new JsonPatchRequest("{\"a\":1}", patchOperations(100), false)))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Should enforce the 100-operation cap through bean validation")
    void shouldEnforceOperationCapThroughBeanValidation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new JsonPatchRequest("{\"a\":1}", patchOperations(100), false)))
                    .isEmpty();

            Set<ConstraintViolation<JsonPatchRequest>> violations =
                    validator.validate(new JsonPatchRequest("{\"a\":1}", patchOperations(101), false));
            assertThat(violations).hasSize(1);
            assertThat(violations).extracting(ConstraintViolation::getMessage)
                    .containsExactly("operations must contain at most 100 entries");
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("operations");

            // The DTO-level cap changes nothing for an in-range payload: same document, same audit.
            JsonPatchResponse response = jsonService.patch(new JsonPatchRequest(
                    "{\"name\":\"Alice\",\"age\":30}", List.of(
                    op("replace", "/name", null, "\"Bob\""),
                    op("add", "/city", null, "\"Paris\""),
                    op("copy", "/years", "/age", null)), false));
            assertThat(response.resultJson().get("name").asText()).isEqualTo("Bob");
            assertThat(response.resultJson().get("city").asText()).isEqualTo("Paris");
            assertThat(response.resultJson().get("years").asInt()).isEqualTo(30);
            assertThat(response.operationsApplied()).isEqualTo(3);
            assertThat(response.operationsRolledBack()).isZero();
            assertThat(response.audit()).extracting(JsonPatchOperation::index).containsExactly(0, 1, 2);
            assertThat(response.audit()).extracting(JsonPatchOperation::status)
                    .containsExactly(JsonPatchOperation.APPLIED, JsonPatchOperation.APPLIED,
                            JsonPatchOperation.APPLIED);
        }
    }

    @Test
    @DisplayName("Should flatten a nested document into ordered dot-path keys")
    void shouldFlattenNestedJson() {
        String json = "{\"name\":\"Alice\",\"address\":{\"city\":\"Paris\","
                + "\"geo\":{\"lat\":48.85,\"lng\":2.35}},\"active\":true}";

        JsonFlattenResponse response = jsonService.flatten(new JsonFlattenRequest(json, null));

        List<String> keys = new ArrayList<>();
        response.resultJson().fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("name", "address.city", "address.geo.lat", "address.geo.lng", "active");
        assertThat(response.keyCount()).isEqualTo(5);
        assertThat(response.maxDepth()).isEqualTo(3);
        assertThat(response.resultJson().get("address.geo.lat").asDouble()).isEqualTo(48.85);
        assertThat(response.resultJson().get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Should keep arrays and empty objects as untouched leaves")
    void shouldKeepArraysAndEmptyObjectsAsLeaves() {
        JsonFlattenResponse flattened =
                jsonService.flatten(new JsonFlattenRequest("{\"a\":{\"b\":[]},\"c\":{}}", null));

        assertThat(flattened.keyCount()).isEqualTo(2);
        assertThat(flattened.maxDepth()).isEqualTo(2);
        assertThat(flattened.resultJson().get("a.b").isArray()).isTrue();
        assertThat(flattened.resultJson().get("a.b")).isEmpty();
        assertThat(flattened.resultJson().get("c").isObject()).isTrue();
        assertThat(flattened.resultJson().get("c")).isEmpty();

        JsonUnflattenResponse rebuilt =
                jsonService.unflatten(new JsonUnflattenRequest(flattened.resultJson().toString(), null));
        assertThat(rebuilt.resultJson().at("/a/b").isArray()).isTrue();
        assertThat(rebuilt.resultJson().at("/c").isObject()).isTrue();
        assertThat(rebuilt.resultJson().at("/c")).isEmpty();
        assertThat(rebuilt.keyCount()).isEqualTo(2);
        assertThat(rebuilt.maxDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should honour a custom separator on both operations")
    void shouldHonourCustomSeparator() {
        JsonFlattenResponse flattened =
                jsonService.flatten(new JsonFlattenRequest("{\"a\":{\"b\":1}}", "/"));

        assertThat(flattened.resultJson().has("a/b")).isTrue();
        assertThat(flattened.resultJson().get("a/b").asInt()).isEqualTo(1);
        assertThat(flattened.keyCount()).isEqualTo(1);

        JsonUnflattenResponse rebuilt =
                jsonService.unflatten(new JsonUnflattenRequest("{\"a/b/c\":2}", "/"));
        assertThat(rebuilt.resultJson().at("/a/b/c").asInt()).isEqualTo(2);
        assertThat(rebuilt.maxDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should round trip a multi-character separator end to end")
    void shouldRoundTripMultiCharacterSeparator() throws JsonProcessingException {
        String json = "{\"outer\":{\"inner\":{\"leaf\":1},\"empty\":{},\"list\":[1,2]},\"top\":true}";
        ObjectMapper mapper = new ObjectMapper();

        JsonFlattenResponse flattened = jsonService.flatten(new JsonFlattenRequest(json, "::"));

        assertThat(flattened.resultJson().has("outer::inner::leaf")).isTrue();
        assertThat(flattened.resultJson().get("outer::empty")).isEmpty();
        assertThat(flattened.keyCount()).isEqualTo(4);
        assertThat(flattened.maxDepth()).isEqualTo(3);

        JsonUnflattenResponse rebuilt = jsonService.unflatten(
                new JsonUnflattenRequest(mapper.writeValueAsString(flattened.resultJson()), "::"));

        assertThat(rebuilt.resultJson()).isEqualTo(mapper.readTree(json));
        assertThat(rebuilt.keyCount()).isEqualTo(flattened.keyCount());
        assertThat(rebuilt.maxDepth()).isEqualTo(flattened.maxDepth());
    }

    @Test
    @DisplayName("Should reject paths a multi-character separator would re-split differently")
    void shouldRejectAmbiguousMultiCharacterSeparator() {
        // "aa" is self-overlapping: {"ba":{"b":1}} joins to "baaab", which unflatten re-splits as
        // ["b","ab"] and silently rebuilds {"b":{"ab":1}} instead of {"ba":{"b":1}} (REQ-04).
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"ba\":{\"b\":1}}", "aa")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous key path 'baaab'")
                .hasMessageContaining("'aa'");
        // The overlap can also introduce an empty segment: {"a":{"x":1}} joins to "aaax".
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"a\":{\"x\":1}}", "aa")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous key path 'aaax'");
        // Proof the refusal is load-bearing rather than cosmetic: the same flat key, handed straight
        // to unflatten, rebuilds a structurally different document.
        assertThat(jsonService.unflatten(new JsonUnflattenRequest("{\"baaab\":1}", "aa"))
                .resultJson().toString()).isEqualTo("{\"b\":{\"ab\":1}}");
        // Non-overlapping multi-character separators keep working.
        assertThat(jsonService.flatten(new JsonFlattenRequest("{\"ba\":{\"b\":1}}", "co"))
                .resultJson().has("ba" + "co" + "b")).isTrue();
    }

    @Test
    @DisplayName("Should reject object keys that contain the separator")
    void shouldRejectSeparatorInsideKeys() {
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"a.b\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain the separator");
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"a\":{\"b/c\":1}}", "/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain the separator");
    }

    @Test
    @DisplayName("Should reject empty member names so flatten output always round-trips")
    void shouldRejectEmptyMemberName() {
        // An empty name would flatten to the flat key "" (or "a."), which unflatten rejects as an empty
        // path segment: refusing it in flatten keeps REQ-04 exact for every document flatten accepts.
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key must not be empty");
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"a\":{\"\":1}}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key must not be empty");

        // A whitespace-only name is not an empty segment, so it is accepted and survives the round trip.
        JsonFlattenResponse flattened =
                jsonService.flatten(new JsonFlattenRequest("{\"a\":{\" \":1}}", null));
        assertThat(flattened.resultJson().has("a. ")).isTrue();
        JsonUnflattenResponse rebuilt =
                jsonService.unflatten(new JsonUnflattenRequest(flattened.resultJson().toString(), null));
        assertThat(rebuilt.resultJson().at("/a/ ").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should rebuild nested objects from flat paths")
    void shouldUnflattenIntoNestedJson() {
        JsonUnflattenResponse response = jsonService.unflatten(
                new JsonUnflattenRequest("{\"a.b.c\":1,\"a.b.d\":2,\"a.e\":3}", null));

        assertThat(response.resultJson().at("/a/b/c").asInt()).isEqualTo(1);
        assertThat(response.resultJson().at("/a/b/d").asInt()).isEqualTo(2);
        assertThat(response.resultJson().at("/a/e").asInt()).isEqualTo(3);
        assertThat(response.keyCount()).isEqualTo(3);
        assertThat(response.maxDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should reject non-object roots on flatten and unflatten")
    void shouldRejectNonObjectRootForFlattenAndUnflatten() {
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("[1,2]", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("json root must be a JSON object");
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest("[1,2]", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("json root must be a JSON object");
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("42", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("json root must be a JSON object");
    }

    @Test
    @DisplayName("Should reject blank or overlong separators, empty segments and deep paths")
    void shouldRejectInvalidFlatDocuments() {
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"a\":1}", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separator must not be blank");
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest("{\"a\":1}", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separator must not be blank");
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest("{\"a\":1}", "abcdefghi")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separator must be at most 8 characters");
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest("{\"a..b\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty path segment in key");
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest("{\".a\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty path segment in key");
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest("{\"a.\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty path segment in key");
        assertThatThrownBy(() -> jsonService.unflatten(
                new JsonUnflattenRequest("{\"" + "a.".repeat(64) + "a\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document depth must not exceed 64");
        String deep = "1";
        for (int i = 0; i < 65; i++) {
            deep = "{\"k\":" + deep + "}";
        }
        String deepDocument = deep;
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest(deepDocument, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document depth must not exceed 64");
    }

    @Test
    @DisplayName("Should reject conflicting flat paths in both key orders")
    void shouldRejectConflictingPaths() {
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest("{\"a\":1,\"a.b\":2}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting path");
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest("{\"a.b\":2,\"a\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting path");
        assertThatThrownBy(() -> jsonService.unflatten(
                new JsonUnflattenRequest("{\"a\":{},\"a.b\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting path");
    }

    @Test
    @DisplayName("Should enforce the 10000 key bound on both operations")
    void shouldEnforceKeyCountBound() {
        assertThatThrownBy(() -> jsonService.unflatten(new JsonUnflattenRequest(flatDocument(10001), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flat document must contain at most 10000 keys");
        assertThatThrownBy(() -> jsonService.flatten(new JsonFlattenRequest(flatDocument(10001), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flattened document must contain at most 10000 keys");

        JsonFlattenResponse boundary =
                jsonService.flatten(new JsonFlattenRequest(flatDocument(10000), null));
        assertThat(boundary.keyCount()).isEqualTo(10000);
    }

    @Test
    @DisplayName("Should round trip flatten to unflatten with structural equality")
    void shouldRoundTripFlattenThenUnflatten() throws JsonProcessingException {
        String original = "{\"id\":7,\"tags\":[\"a\",\"b\"],\"meta\":{},\"nested\":{\"one\":{\"two\":null,"
                + "\"three\":[],\"four\":false},\"five\":\"text\"},\"number\":1.5}";
        ObjectMapper mapper = new ObjectMapper();

        JsonFlattenResponse flattened = jsonService.flatten(new JsonFlattenRequest(original, null));
        JsonUnflattenResponse rebuilt = jsonService.unflatten(
                new JsonUnflattenRequest(mapper.writeValueAsString(flattened.resultJson()), null));

        assertThat(rebuilt.resultJson()).isEqualTo(mapper.readTree(original));
        assertThat(rebuilt.keyCount()).isEqualTo(flattened.keyCount());
        assertThat(rebuilt.maxDepth()).isEqualTo(flattened.maxDepth());
    }

    @Test
    @DisplayName("Should require a non-blank json body on flatten and unflatten requests")
    void shouldRequireJsonOnFlattenAndUnflattenRequests() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new JsonFlattenRequest("", null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("json content is required");
            assertThat(validator.validate(new JsonUnflattenRequest("   ", null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("json content is required");
            assertThat(validator.validate(new JsonFlattenRequest("{\"a\":1}", null))).isEmpty();
            assertThat(validator.validate(new JsonUnflattenRequest("{\"a\":1}", "."))).isEmpty();
        }
    }

    @Test
    @DisplayName("Should merge nested objects and delete null members with merge patch")
    void shouldMergePatchNestedObjectsAndDeleteNullMembers() throws JsonProcessingException {
        JsonMergePatchRequest request = new JsonMergePatchRequest(
                "{\"a\":1,\"b\":{\"c\":2,\"d\":3}}",
                "{\"b\":{\"c\":null,\"e\":4},\"f\":[1,2]}");

        JsonMergePatchResponse response = jsonService.mergePatch(request);

        ObjectMapper mapper = new ObjectMapper();
        assertThat(response.resultJson())
                .isEqualTo(mapper.readTree("{\"a\":1,\"b\":{\"d\":3,\"e\":4},\"f\":[1,2]}"));
        assertThat(response.changed()).isTrue();
        assertThat(response.removedKeys()).isEqualTo(1);
        assertThat(response.sizeBytes())
                .isEqualTo(mapper.writeValueAsString(response.resultJson()).getBytes(StandardCharsets.UTF_8).length);
        assertThat(mapper.writeValueAsString(response.resultJson())).doesNotContain("\n");
    }

    @Test
    @DisplayName("Should replace the whole target when the merge patch is not an object")
    void shouldReplaceTargetWithNonObjectPatch() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        JsonMergePatchResponse numberPatch = jsonService.mergePatch(
                new JsonMergePatchRequest("{\"a\":1,\"b\":{\"c\":2}}", "7"));
        assertThat(numberPatch.resultJson()).isEqualTo(mapper.readTree("7"));
        assertThat(numberPatch.changed()).isTrue();
        assertThat(numberPatch.removedKeys()).isZero();

        JsonMergePatchResponse arrayPatch = jsonService.mergePatch(
                new JsonMergePatchRequest("{\"a\":1}", "[1,2]"));
        assertThat(arrayPatch.resultJson()).isEqualTo(mapper.readTree("[1,2]"));
        assertThat(arrayPatch.changed()).isTrue();

        JsonMergePatchResponse scalarPatch = jsonService.mergePatch(
                new JsonMergePatchRequest("\"text\"", "\"other\""));
        assertThat(scalarPatch.resultJson()).isEqualTo(mapper.readTree("\"other\""));

        JsonMergePatchResponse unchanged = jsonService.mergePatch(
                new JsonMergePatchRequest("7", "7"));
        assertThat(unchanged.changed()).isFalse();
    }

    @Test
    @DisplayName("Should build a fresh object when the merge patch target is not an object")
    void shouldMergeObjectPatchIntoNonObjectTarget() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        JsonMergePatchResponse response = jsonService.mergePatch(
                new JsonMergePatchRequest("[1,2]", "{\"a\":1,\"b\":{\"c\":null}}"));

        assertThat(response.resultJson()).isEqualTo(mapper.readTree("{\"a\":1,\"b\":{}}"));
        assertThat(response.changed()).isTrue();
        // "c" never existed in the (non-object) target, so nothing was removed.
        assertThat(response.removedKeys()).isZero();
    }

    @Test
    @DisplayName("Should replace arrays and scalars wholesale during merge patch")
    void shouldReplaceArraysAndScalars() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        JsonMergePatchResponse response = jsonService.mergePatch(new JsonMergePatchRequest(
                "{\"a\":[1,2],\"b\":{\"c\":1},\"d\":\"x\"}",
                "{\"a\":[3],\"b\":5,\"d\":{\"e\":true}}"));

        assertThat(response.resultJson())
                .isEqualTo(mapper.readTree("{\"a\":[3],\"b\":5,\"d\":{\"e\":true}}"));
        assertThat(response.changed()).isTrue();
        assertThat(response.removedKeys()).isZero();
    }

    @Test
    @DisplayName("Should ignore and not count null merge patch members for absent keys")
    void shouldDeleteOnlyNullPatchMembers() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        JsonMergePatchResponse response = jsonService.mergePatch(new JsonMergePatchRequest(
                "{\"a\":1,\"b\":{\"c\":2,\"d\":3}}",
                "{\"a\":null,\"b\":{\"c\":null,\"z\":null},\"missing\":null}"));

        assertThat(response.resultJson()).isEqualTo(mapper.readTree("{\"b\":{\"d\":3}}"));
        assertThat(response.removedKeys()).isEqualTo(2);
        assertThat(response.changed()).isTrue();

        JsonMergePatchResponse noop = jsonService.mergePatch(
                new JsonMergePatchRequest("{\"a\":1}", "{\"missing\":null}"));
        assertThat(noop.resultJson()).isEqualTo(mapper.readTree("{\"a\":1}"));
        assertThat(noop.changed()).isFalse();
        assertThat(noop.removedKeys()).isZero();
    }

    @Test
    @DisplayName("Should treat an empty merge patch as a no-op without changing size")
    void shouldTreatEmptyMergePatchAsNoop() {
        JsonMergePatchRequest request = new JsonMergePatchRequest("{\"a\":1}", "{}");

        JsonMergePatchResponse response = jsonService.mergePatch(request);

        assertThat(response.changed()).isFalse();
        assertThat(response.removedKeys()).isZero();
        assertThat(response.sizeBytes()).isEqualTo("{\"a\":1}".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Should not mutate merge patch inputs across repeated calls")
    void shouldNotMutateMergePatchInputs() {
        String json = "{\"a\":1,\"b\":{\"c\":2}}";
        String patch = "{\"b\":{\"c\":null,\"d\":3},\"e\":[1]}";
        JsonMergePatchRequest request = new JsonMergePatchRequest(json, patch);

        JsonMergePatchResponse first = jsonService.mergePatch(request);
        JsonMergePatchResponse second = jsonService.mergePatch(request);

        assertThat(request.json()).isEqualTo("{\"a\":1,\"b\":{\"c\":2}}");
        assertThat(request.patch()).isEqualTo("{\"b\":{\"c\":null,\"d\":3},\"e\":[1]}");
        assertThat(second.resultJson()).isEqualTo(first.resultJson());
        assertThat(second.removedKeys()).isEqualTo(first.removedKeys());
        assertThat(second.changed()).isEqualTo(first.changed());
        assertThat(first.resultJson().toString()).isEqualTo("{\"a\":1,\"b\":{\"d\":3},\"e\":[1]}");
    }

    @Test
    @DisplayName("Should reject malformed JSON in merge patch requests")
    void shouldRejectMalformedMergePatchJson() {
        assertThatThrownBy(() -> jsonService.mergePatch(new JsonMergePatchRequest("{", "{\"a\":1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON");
        assertThatThrownBy(() -> jsonService.mergePatch(new JsonMergePatchRequest("{\"a\":1}", "[1,")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON");
    }

    @Test
    @DisplayName("Should reject merge patches nested beyond the depth bound")
    void shouldRejectDeepMergePatchBeyondMaxDepth() {
        String deep = "1";
        for (int i = 0; i < 64; i++) {
            deep = "{\"k\":" + deep + "}";
        }
        String deepPatch = deep;
        assertThatThrownBy(() -> jsonService.mergePatch(new JsonMergePatchRequest("{\"k\":1}", deepPatch)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("patch depth must not exceed 64");

        String allowed = "1";
        for (int i = 0; i < 63; i++) {
            allowed = "{\"k\":" + allowed + "}";
        }
        String allowedPatch = allowed;
        assertThat(jsonService.mergePatch(new JsonMergePatchRequest("{\"k\":1}", allowedPatch)).resultJson())
                .isNotNull();
    }

    @Test
    @DisplayName("Should validate merge patch request fields")
    void shouldValidateMergePatchRequest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new JsonMergePatchRequest("", "{}")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("json content is required");
            assertThat(validator.validate(new JsonMergePatchRequest("{}", " ")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("patch content is required");
            assertThat(validator.validate(new JsonMergePatchRequest("{}", "{}"))).isEmpty();
        }
    }

    // ---------------------------------------------------------------- JSON Diff

    @Test
    @DisplayName("Should diff nested objects reporting added, removed and changed paths")
    void shouldDiffNestedObjectsReportingAddedRemovedAndChanged() {
        JsonDiffResponse response = jsonService.diff(new JsonDiffRequest(
                "{\"a\":1,\"b\":{\"c\":2,\"e\":{\"x\":1}},\"g\":\"keep\"}",
                "{\"a\":1,\"b\":{\"c\":5},\"f\":true}"));

        assertThat(response.added()).containsExactly("f");
        assertThat(response.removed()).containsExactly("b.e", "g");
        assertThat(response.changed()).containsOnlyKeys("b.c", "b.e", "g", "f");
        assertThat(response.summary()).isEqualTo("1 added, 2 removed, 1 changed");
    }

    @Test
    @DisplayName("Should diff arrays by index and report out-of-range elements")
    void shouldDiffArraysByIndexAndReportOutOfRangeElements() {
        JsonDiffResponse shrunk = jsonService.diff(new JsonDiffRequest(
                "{\"d\":[1,2,3]}", "{\"d\":[1,9]}"));

        assertThat(shrunk.added()).isEmpty();
        assertThat(shrunk.removed()).containsExactly("d[2]");
        assertThat(shrunk.changed().get("d[1]"))
                .isEqualTo(new JsonDiffDetail("d[1]", "2", "9", JsonDiffDetail.CHANGED));
        assertThat(shrunk.summary()).isEqualTo("0 added, 1 removed, 1 changed");

        JsonDiffResponse grown = jsonService.diff(new JsonDiffRequest(
                "{\"d\":[1]}", "{\"d\":[1,2]}"));

        assertThat(grown.added()).containsExactly("d[1]");
        assertThat(grown.removed()).isEmpty();
        assertThat(grown.summary()).isEqualTo("1 added, 0 removed, 0 changed");
    }

    @Test
    @DisplayName("Should report no changes for identical documents")
    void shouldReportNoChangesForIdenticalDocuments() {
        JsonDiffResponse response = jsonService.diff(new JsonDiffRequest(
                "{\"a\":[1,{\"b\":null}],\"c\":\"x\"}", "{\"c\":\"x\",\"a\":[1,{\"b\":null}]}"));

        assertThat(response.added()).isEmpty();
        assertThat(response.removed()).isEmpty();
        assertThat(response.changed()).isEmpty();
        assertThat(response.summary()).isEqualTo("No differences found");
    }

    @Test
    @DisplayName("Should report a scalar or type mismatch of the whole document at the root sentinel")
    void shouldReportRootSentinelForScalarAndTypeMismatch() {
        JsonDiffResponse scalars = jsonService.diff(new JsonDiffRequest("1", "2"));

        assertThat(scalars.added()).isEmpty();
        assertThat(scalars.removed()).isEmpty();
        assertThat(scalars.changed()).containsOnlyKeys("$");
        assertThat(scalars.changed().get("$"))
                .isEqualTo(new JsonDiffDetail("$", "1", "2", JsonDiffDetail.CHANGED));
        assertThat(scalars.summary()).isEqualTo("0 added, 0 removed, 1 changed");

        JsonDiffResponse types = jsonService.diff(new JsonDiffRequest("{\"a\":1}", "[1]"));

        assertThat(types.changed()).containsOnlyKeys("$");
        assertThat(types.changed().get("$").oldValue()).isEqualTo("{\"a\":1}");
        assertThat(types.changed().get("$").newValue()).isEqualTo("[1]");
    }

    @Test
    @DisplayName("Should render dotted member names in brackets so paths stay unambiguous")
    void shouldRenderDottedMemberNamesInBracketsSoPathsStayUnambiguous() {
        JsonDiffResponse response = jsonService.diff(new JsonDiffRequest(
                "{\"x.y\":1,\"a[0]\":2,\"z\":{\"p'q\":3}}", "{\"x.y\":9,\"a[0]\":8,\"z\":{\"p'q\":7}}"));

        assertThat(response.changed())
                .containsOnlyKeys("['x.y']", "['a[0]']", "z['p\\'q']");
        assertThat(response.changed().get("['x.y']").oldValue()).isEqualTo("1");
        assertThat(response.changed().get("['x.y']").newValue()).isEqualTo("9");
        assertThat(response.changed().get("z['p\\'q']").changeType()).isEqualTo(JsonDiffDetail.CHANGED);
        assertThat(response.summary()).isEqualTo("0 added, 0 removed, 3 changed");
    }

    @Test
    @DisplayName("Should carry path, old value, new value and change type for every detail")
    void shouldCarryOldAndNewValuesAndChangeTypeForEveryDetail() {
        JsonDiffResponse response = jsonService.diff(new JsonDiffRequest(
                "{\"keep\":1,\"gone\":{\"k\":[1]},\"swap\":true}", "{\"keep\":1,\"fresh\":null,\"swap\":\"yes\"}"));

        assertThat(response.changed().get("gone"))
                .isEqualTo(new JsonDiffDetail("gone", "{\"k\":[1]}", null, JsonDiffDetail.REMOVED));
        assertThat(response.changed().get("fresh"))
                .isEqualTo(new JsonDiffDetail("fresh", null, "null", JsonDiffDetail.ADDED));
        assertThat(response.changed().get("swap"))
                .isEqualTo(new JsonDiffDetail("swap", "true", "\"yes\"", JsonDiffDetail.CHANGED));
        response.changed().forEach((path, detail) -> assertThat(detail.path()).isEqualTo(path));
        assertThat(response.summary()).isEqualTo("1 added, 1 removed, 1 changed");
    }

    @Test
    @DisplayName("Should reject malformed jsonA and jsonB with the offending field named")
    void shouldRejectMalformedJsonAAndJsonBLabelled() {
        assertThatThrownBy(() -> jsonService.diff(new JsonDiffRequest("{", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid JSON content in jsonA: ");
        assertThatThrownBy(() -> jsonService.diff(new JsonDiffRequest("{}", "[1,")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid JSON content in jsonB: ");
    }

    @Test
    @DisplayName("Should reject a document nested deeper than the depth cap")
    void shouldRejectTooDeepDocumentForDiff() {
        String deep = "1";
        for (int i = 0; i < 64; i++) {
            deep = "{\"k\":" + deep + "}";
        }
        String deepSide = deep;
        assertThatThrownBy(() -> jsonService.diff(new JsonDiffRequest(deepSide, "{\"k\":1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jsonA depth must not exceed 64");

        String allowed = "1";
        for (int i = 0; i < 63; i++) {
            allowed = "{\"k\":" + allowed + "}";
        }
        String allowedSide = allowed;
        assertThat(jsonService.diff(new JsonDiffRequest(allowedSide, "{\"k\":1}")).summary())
                .isEqualTo("0 added, 0 removed, 1 changed");
    }

    @Test
    @DisplayName("Should reject a diff exceeding the change cap")
    void shouldRejectDiffExceedingChangeCap() {
        String huge = flatDocument(MAX_KEYS + 1);
        assertThatThrownBy(() -> jsonService.diff(new JsonDiffRequest("{}", huge)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diff must not contain more than " + MAX_KEYS + " changes");
    }

    @Test
    @DisplayName("Should validate the diff request fields")
    void shouldValidateJsonDiffRequest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new JsonDiffRequest("", "{}")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("jsonA content is required");
            assertThat(validator.validate(new JsonDiffRequest("{}", " ")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("jsonB content is required");
            assertThat(validator.validate(new JsonDiffRequest("{}", "{}"))).isEmpty();
        }
    }

    @Test
    @DisplayName("Should quote CSV fields carrying the delimiter, a quote, CR or LF")
    void shouldQuoteDelimitersQuotesAndEmbeddedNewlines() {
        String json = "[{\"name\":\"a,b\",\"quote\":\"say \\\"hi\\\"\",\"multiline\":\"l1\\nl2\","
                + "\"cr\":\"x\\ry\",\"plain\":\"ok\",\"missing\":null}]";

        JsonToCsvResponse response = jsonService.toCsv(new JsonToCsvRequest(json, null));

        assertThat(response.csv()).isEqualTo(
                "name,quote,multiline,cr,plain,missing\r\n"
                        + "\"a,b\",\"say \"\"hi\"\"\",\"l1\nl2\",\"x\ry\",ok,");
        assertThat(response.headers()).containsExactly("name", "quote", "multiline", "cr", "plain", "missing");
        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(response.columnCount()).isEqualTo(6);
        assertThat(response.sizeBytes()).isEqualTo(response.csv().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Should emit tab separated CSV for both the \\t escape and a literal tab")
    void shouldEmitTabSeparatedOutput() {
        String json = "[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"b\":null}]";

        JsonToCsvResponse escaped = jsonService.toCsv(new JsonToCsvRequest(json, "\\t"));
        JsonToCsvResponse literal = jsonService.toCsv(new JsonToCsvRequest(json, "\t"));

        assertThat(escaped.csv()).isEqualTo("a\tb\r\n1\t2\r\n3\t");
        assertThat(literal.csv()).isEqualTo(escaped.csv());
    }

    @Test
    @DisplayName("Should default the delimiter to comma and return an empty export for an empty array")
    void shouldDefaultCsvOptions() {
        assertThat(jsonService.toCsv(new JsonToCsvRequest("[{\"a\":\"1\",\"b\":\"2\"}]", null)).csv())
                .isEqualTo("a,b\r\n1,2");
        assertThat(jsonService.toCsv(new JsonToCsvRequest("[{\"a\":\"1\"}]", "")).csv())
                .isEqualTo("a\r\n1");

        JsonToCsvResponse empty = jsonService.toCsv(new JsonToCsvRequest("[]", null));
        assertThat(empty.csv()).isEmpty();
        assertThat(empty.rowCount()).isZero();
        assertThat(empty.columnCount()).isZero();
        assertThat(empty.headers()).isEmpty();

        JsonFromCsvResponse imported = jsonService.fromCsv(new JsonFromCsvRequest("a,b\r\n1,2", null, null));
        assertThat(imported.columns()).containsExactly("a", "b");
        assertThat(imported.rowCount()).isEqualTo(1);
        assertThat(imported.resultJson().get(0).get("b").asText()).isEqualTo("2");
    }

    @Test
    @DisplayName("Should parse quoted CSV fields, doubled quotes and LF/CRLF/CR line endings")
    void shouldParseQuotedFieldsAndLineEndings() {
        String csv = "\"a,b\",\"say \"\"hi\"\"\",\"l1\nl2\"\r\nx,y,z\np,q,r\r";

        JsonFromCsvResponse response = jsonService.fromCsv(new JsonFromCsvRequest(csv, ",", true));

        assertThat(response.columns()).containsExactly("a,b", "say \"hi\"", "l1\nl2");
        assertThat(response.rowCount()).isEqualTo(2);
        assertThat(response.columnCount()).isEqualTo(3);
        assertThat(response.resultJson().get(0).get("a,b").asText()).isEqualTo("x");
        assertThat(response.resultJson().get(1).get("say \"hi\"").asText()).isEqualTo("q");
        assertThat(response.resultJson().get(1).get("l1\nl2").asText()).isEqualTo("r");
    }

    @Test
    @DisplayName("Should tolerate a UTF-8 BOM when importing CSV")
    void shouldTolerateUtf8BomOnImport() {
        JsonFromCsvResponse response = jsonService.fromCsv(new JsonFromCsvRequest("\uFEFFa,b\r\n1,2", ",", true));

        assertThat(response.columns()).containsExactly("a", "b");
        assertThat(response.resultJson().get(0).fieldNames()).toIterable().containsExactly("a", "b");
        assertThat(response.resultJson().get(0).get("a").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("Should map blank headers to columnN and fall back to column1..N without a header")
    void shouldUsePositionalColumnsWhenHasHeaderIsFalse() {
        JsonFromCsvResponse blankHeader = jsonService.fromCsv(new JsonFromCsvRequest(",b\r\n1,2", ",", true));
        assertThat(blankHeader.columns()).containsExactly("column1", "b");
        assertThat(blankHeader.resultJson().get(0).get("column1").asText()).isEqualTo("1");
        // hasHeader omitted defaults to true, so the first record is still the header.
        JsonFromCsvResponse omittedHeaderFlag = jsonService.fromCsv(new JsonFromCsvRequest(",b\r\n1,2", null, null));
        assertThat(omittedHeaderFlag.columns()).containsExactly("column1", "b");
        assertThat(omittedHeaderFlag.rowCount()).isEqualTo(1);

        JsonFromCsvResponse headerless = jsonService.fromCsv(new JsonFromCsvRequest("1,2\n3,4", ",", false));
        assertThat(headerless.columns()).containsExactly("column1", "column2");
        assertThat(headerless.rowCount()).isEqualTo(2);
        assertThat(headerless.resultJson().get(1).get("column2").asText()).isEqualTo("4");
        // Short rows are padded with empty cells so every object carries every column.
        JsonFromCsvResponse ragged = jsonService.fromCsv(new JsonFromCsvRequest("1,2\n3", ",", false));
        assertThat(ragged.resultJson().get(1).get("column2").asText()).isEmpty();
    }

    @Test
    @DisplayName("Should round trip flat string valued objects through CSV")
    void shouldRoundTripStringValuedFlatObjects() throws JsonProcessingException {
        String json = "[{\"name\":\"Ada, Lovelace\",\"note\":\"line1\\r\\nline2\","
                + "\"quote\":\"a \\\"quoted\\\" word\",\"empty\":\"\",\"tabbed\":\"a\\tb\"}]";

        JsonToCsvResponse exported = jsonService.toCsv(new JsonToCsvRequest(json, null));
        JsonFromCsvResponse imported =
                jsonService.fromCsv(new JsonFromCsvRequest(exported.csv(), null, true));

        assertThat(imported.rowCount()).isEqualTo(1);
        assertThat(imported.resultJson()).isEqualTo(new ObjectMapper().readTree(json));
    }

    @Test
    @DisplayName("Should reject non-array JSON when exporting to CSV")
    void shouldRejectNonArrayJsonContent() {
        assertThatThrownBy(() -> jsonService.toCsv(new JsonToCsvRequest("{\"a\":1}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("json content must be an array of objects");
        assertThatThrownBy(() -> jsonService.toCsv(new JsonToCsvRequest("[1,2,3]", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("json array must contain only objects");
    }

    @Test
    @DisplayName("Should reject nested values when exporting to CSV")
    void shouldRejectNestedValuesInCsvExport() {
        assertThatThrownBy(() -> jsonService.toCsv(new JsonToCsvRequest("[{\"a\":{\"b\":1}}]", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nested values are not supported in CSV export");
        assertThatThrownBy(() -> jsonService.toCsv(new JsonToCsvRequest("[{\"a\":[1,2]}]", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nested values are not supported in CSV export");
    }

    @Test
    @DisplayName("Should reject malformed CSV input")
    void shouldRejectMalformedCsv() {
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest("\"a,b", ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unterminated quoted field");
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest("a,\"b\"x,c", ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unexpected character after closing quote");
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest("a,b\r\n1,2,3", ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("row 2 has 3 fields but the header has 2");
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest("", ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("csv content is required");
    }

    @Test
    @DisplayName("Should reject duplicate CSV header names")
    void shouldRejectDuplicateHeaderNames() {
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest("a,b,a\r\n1,2,3", ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate header name: a");
    }

    @Test
    @DisplayName("Should enforce the row, column and cell bounds on both conversion directions")
    void shouldRejectOversizedCsvInputAndOutput() {
        StringBuilder wide = new StringBuilder("[{");
        for (int i = 0; i <= MAX_KEYS; i++) {
            if (i > 0) {
                wide.append(',');
            }
            wide.append("\"c").append(i).append("\":1");
        }
        wide.append("}]");
        assertThatThrownBy(() -> jsonService.toCsv(new JsonToCsvRequest(wide.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("columns must not exceed " + MAX_KEYS);

        StringBuilder tall = new StringBuilder("[");
        for (int i = 0; i <= MAX_KEYS; i++) {
            if (i > 0) {
                tall.append(',');
            }
            tall.append("{}");
        }
        tall.append(']');
        assertThatThrownBy(() -> jsonService.toCsv(new JsonToCsvRequest(tall.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rows must not exceed " + MAX_KEYS);

        StringBuilder importTall = new StringBuilder("h");
        for (int i = 0; i <= MAX_KEYS; i++) {
            importTall.append("\r\nv");
        }
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest(importTall.toString(), ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rows must not exceed " + MAX_KEYS);

        StringBuilder importWide = new StringBuilder("h0");
        for (int i = 1; i <= MAX_KEYS; i++) {
            importWide.append(",h").append(i);
        }
        importWide.append("\r\n");
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest(importWide.toString(), ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("columns must not exceed " + MAX_KEYS);

        String dataRow = "x" + ",x".repeat(500);
        StringBuilder grid = new StringBuilder("h0");
        for (int i = 1; i <= 500; i++) {
            grid.append(",h").append(i);
        }
        for (int r = 0; r < 2000; r++) {
            grid.append("\r\n").append(dataRow);
        }
        assertThatThrownBy(() -> jsonService.fromCsv(new JsonFromCsvRequest(grid.toString(), ",", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("conversion exceeds " + MAX_CELLS + " cells");
    }

    @Test
    @DisplayName("Should convert CSV to JSON with delimiters and header mapping")
    void shouldConvertCsvToJsonWithDelimiterAndHeaderMapping() {
        JsonCsvResponse defaults = jsonService.csvToJson(new JsonCsvRequest("name,age\r\nAlice,30\r\nBob,25", null, null));
        assertThat(defaults.headers()).containsExactly("name", "age");
        assertThat(defaults.rowCount()).isEqualTo(2);
        assertThat(defaults.columnCount()).isEqualTo(2);
        assertThat(defaults.resultJson().toString())
                .isEqualTo("[{\"name\":\"Alice\",\"age\":\"30\"},{\"name\":\"Bob\",\"age\":\"25\"}]");

        JsonCsvResponse semicolons = jsonService.csvToJson(new JsonCsvRequest("a;b\n1;2", ";", null));
        assertThat(semicolons.headers()).containsExactly("a", "b");
        assertThat(semicolons.resultJson().get(0).path("b").asText()).isEqualTo("2");

        JsonCsvResponse tabs = jsonService.csvToJson(new JsonCsvRequest("a\tb\n1\t2", "\\t", null));
        assertThat(tabs.headers()).containsExactly("a", "b");
        assertThat(tabs.resultJson().get(0).path("b").asText()).isEqualTo("2");
        assertThat(jsonService.csvToJson(new JsonCsvRequest("a\tb\n1\t2", "\t", null)).resultJson())
                .isEqualTo(tabs.resultJson());

        JsonCsvResponse mapped = jsonService.csvToJson(new JsonCsvRequest("name,age\nAlice,30",
                null, java.util.Map.of("name", "fullName")));
        assertThat(mapped.headers()).containsExactly("fullName", "age");
        assertThat(mapped.resultJson().get(0).path("fullName").asText()).isEqualTo("Alice");
        assertThat(mapped.resultJson().get(0).path("name").isMissingNode()).isTrue();

        JsonCsvResponse blankHeader = jsonService.csvToJson(new JsonCsvRequest("h,,z\n1,2,3", null, null));
        assertThat(blankHeader.headers()).containsExactly("h", "column2", "z");
        assertThat(blankHeader.resultJson().get(0).path("column2").asText()).isEqualTo("2");

        JsonCsvResponse shortRow = jsonService.csvToJson(new JsonCsvRequest("a,b\n1", null, null));
        assertThat(shortRow.resultJson().get(0).path("b").asText()).isEmpty();

        JsonCsvResponse bom = jsonService.csvToJson(new JsonCsvRequest("\uFEFFa,b\n1,2", null, null));
        assertThat(bom.headers()).containsExactly("a", "b");

        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("a,b,a\n1,2,3", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate header name: a");
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("a,b\n1,2,3", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("row 2 has 3 fields but the header has 2");
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("a,b\n1,2",
                null, java.util.Map.of("c", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("headerMapping key 'c' does not match any header");
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("a,b\n1,2",
                null, java.util.Map.of("a", " "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("headerMapping value for 'a' must not be blank");
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("a,b\n1,2",
                null, java.util.Map.of("a", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("headerMapping renames header 'a' to duplicate key 'b'");

        StringBuilder tall = new StringBuilder("h");
        for (int i = 0; i <= MAX_KEYS; i++) {
            tall.append("\r\nv");
        }
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest(tall.toString(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rows must not exceed " + MAX_KEYS);

        StringBuilder wide = new StringBuilder("h0");
        for (int i = 1; i <= MAX_KEYS; i++) {
            wide.append(",h").append(i);
        }
        wide.append("\r\n");
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest(wide.toString(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("columns must not exceed " + MAX_KEYS);

        StringBuilder grid = new StringBuilder("h0");
        for (int i = 1; i <= 500; i++) {
            grid.append(",h").append(i);
        }
        String dataRow = "x" + ",x".repeat(500);
        for (int r = 0; r < 2000; r++) {
            grid.append("\r\n").append(dataRow);
        }
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest(grid.toString(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("conversion exceeds " + MAX_CELLS + " cells");
    }

    @Test
    @DisplayName("Should validate and reject invalid CSV to JSON requests")
    void shouldRejectInvalidCsvToJsonRequests() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new JsonCsvRequest(" ", null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("csvContent is required");
            assertThat(validator.validate(new JsonCsvRequest(null, null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("csvContent is required");
            assertThat(validator.validate(new JsonCsvRequest("a".repeat(1_000_001), null, null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("csvContent must be at most 1000000 characters");
            assertThat(validator.validate(new JsonCsvRequest("a,b\n1,2", ",,", null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("delimiter must be a single character");
            assertThat(validator.validate(new JsonCsvRequest("a,b\n1,2", ";", java.util.Map.of("a", "x"))))
                    .isEmpty();
        }

        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("   ", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("csvContent is required");
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("\"a,b", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unterminated quoted field");
        assertThatThrownBy(() -> jsonService.csvToJson(new JsonCsvRequest("a,\"b\"x,c", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unexpected character after closing quote");
    }

    @Test
    @DisplayName("Should validate CSV request fields")
    void shouldValidateCsvRequestFields() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new JsonToCsvRequest(" ", null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("json content is required");
            assertThat(validator.validate(new JsonToCsvRequest("a".repeat(1_000_001), null)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("json content must be at most 1000000 characters");
            assertThat(validator.validate(new JsonToCsvRequest("[{\"a\":1}]", null))).isEmpty();

            assertThat(validator.validate(new JsonFromCsvRequest("", null, true)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("csv content is required");
            assertThat(validator.validate(new JsonFromCsvRequest("a".repeat(1_000_001), null, true)))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("csv content must be at most 1000000 characters");
            assertThat(validator.validate(new JsonFromCsvRequest("a\r\n1", null, null))).isEmpty();
        }
    }

    @Test
    @DisplayName("Should convert a JSON array to NDJSON with one compact value per line")
    void shouldConvertJsonArrayToNdjson() {
        JsonToNdjsonResponse response = jsonService.toNdjson(
                new JsonToNdjsonRequest("[{\"a\":1},{\"b\":2}]"));

        assertThat(response.ndjson()).isEqualTo("{\"a\":1}\n{\"b\":2}");
        assertThat(response.lineCount()).isEqualTo(2);
        assertThat(response.ndjson()).doesNotEndWith("\n");
        assertThat(response.sizeBytes())
                .isEqualTo(response.ndjson().getBytes(StandardCharsets.UTF_8).length);

        JsonToNdjsonResponse mixed = jsonService.toNdjson(new JsonToNdjsonRequest("[1,[2],null]"));
        assertThat(mixed.ndjson()).isEqualTo("1\n[2]\nnull");
        assertThat(mixed.lineCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should return empty NDJSON for an empty array")
    void shouldReturnEmptyNdjsonForEmptyArray() {
        JsonToNdjsonResponse response = jsonService.toNdjson(new JsonToNdjsonRequest("[]"));

        assertThat(response.ndjson()).isEmpty();
        assertThat(response.lineCount()).isZero();
        assertThat(response.sizeBytes()).isZero();
    }

    @Test
    @DisplayName("Should reject a non-array root and malformed JSON for NDJSON export")
    void shouldRejectNonArrayRootForNdjson() {
        for (String json : List.of("{\"a\":1}", "7", "\"x\"")) {
            assertThatThrownBy(() -> jsonService.toNdjson(new JsonToNdjsonRequest(json)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("json content must be an array");
        }
        assertThatThrownBy(() -> jsonService.toNdjson(new JsonToNdjsonRequest("{not json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON content:");
    }

    @Test
    @DisplayName("Should convert NDJSON lines back to a JSON array in input order")
    void shouldConvertNdjsonToJsonArray() {
        var response = jsonService.fromNdjson(new JsonFromNdjsonRequest(
                "{\"a\":1}\n[1,2]\n\"text\"\ntrue\nnull"));

        assertThat(response.recordCount()).isEqualTo(5);
        assertThat(response.resultJson().isArray()).isTrue();
        assertThat(response.resultJson()).hasSize(5);
        assertThat(response.resultJson().get(0).path("a").asInt()).isEqualTo(1);
        assertThat(response.resultJson().get(1).toString()).isEqualTo("[1,2]");
        assertThat(response.resultJson().get(2).asText()).isEqualTo("text");
        assertThat(response.resultJson().get(3).asBoolean()).isTrue();
        assertThat(response.resultJson().get(4).isNull()).isTrue();
    }

    @Test
    @DisplayName("Should ignore blank lines and CRLF endings when importing NDJSON")
    void shouldIgnoreBlankLinesAndCrlf() {
        var response = jsonService.fromNdjson(new JsonFromNdjsonRequest(
                "{\"a\":1}\r\n\r\n{\"b\":2}\r\n"));

        assertThat(response.recordCount()).isEqualTo(2);
        assertThat(response.resultJson().get(0).path("a").asInt()).isEqualTo(1);
        assertThat(response.resultJson().get(1).path("b").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should report the 1-based physical line number of a malformed NDJSON line")
    void shouldReportPhysicalLineNumberForMalformedLine() {
        assertThatThrownBy(() -> jsonService.fromNdjson(
                new JsonFromNdjsonRequest("{\"a\":1}\n\n{not json}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON on line 3");

        assertThatThrownBy(() -> jsonService.fromNdjson(new JsonFromNdjsonRequest("1\n2\n]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON on line 3");
    }

    @Test
    @DisplayName("Should treat an empty NDJSON document as an empty array")
    void shouldAllowEmptyNdjsonDocument() {
        for (String document : java.util.Arrays.asList(null, "", "   ", "\n\n")) {
            var response = jsonService.fromNdjson(new JsonFromNdjsonRequest(document));
            assertThat(response.recordCount()).isZero();
            assertThat(response.resultJson().isArray()).isTrue();
            assertThat(response.resultJson()).isEmpty();
        }
    }

    @Test
    @DisplayName("Should enforce the 10000 record bound on NDJSON import")
    void shouldEnforceNdjsonRecordBound() {
        String tenThousand = IntStream.range(0, 10000)
                .mapToObj(String::valueOf)
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
        assertThat(jsonService.fromNdjson(new JsonFromNdjsonRequest(tenThousand)).recordCount())
                .isEqualTo(10000);

        String tooMany = tenThousand + "\n10000";
        assertThatThrownBy(() -> jsonService.fromNdjson(new JsonFromNdjsonRequest(tooMany)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records must not exceed 10000");
    }

    @Test
    @DisplayName("Should round-trip NDJSON and empty documents")
    void shouldRoundTripNdjsonAndEmptyDocuments() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String array = "[{\"a\":1,\"b\":[1,2]},\"text\",null,true,7.5]";

        JsonToNdjsonResponse exported = jsonService.toNdjson(new JsonToNdjsonRequest(array));
        var imported = jsonService.fromNdjson(new JsonFromNdjsonRequest(exported.ndjson()));

        assertThat(imported.resultJson()).isEqualTo(objectMapper.readTree(array));
        assertThat(imported.recordCount()).isEqualTo(5);

        JsonToNdjsonResponse emptyArray = jsonService.toNdjson(new JsonToNdjsonRequest("[]"));
        assertThat(jsonService.fromNdjson(new JsonFromNdjsonRequest(emptyArray.ndjson())).resultJson())
                .isEmpty();
        assertThat(jsonService.fromNdjson(new JsonFromNdjsonRequest("")).resultJson()).isEmpty();
    }

    @Test
    @DisplayName("Should validate the NDJSON request fields")
    void shouldValidateNdjsonRequestFields() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new JsonToNdjsonRequest(" ")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("json content is required");
            assertThat(validator.validate(new JsonToNdjsonRequest("[{\"a\":1}]"))).isEmpty();

            assertThat(validator.validate(new JsonFromNdjsonRequest(null))).isEmpty();
            assertThat(validator.validate(new JsonFromNdjsonRequest("a".repeat(1_000_001))))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("ndjson content must be at most 1000000 characters");
        }
    }

    // ------------------------------------------------------------------ json/pointer

    private static final String POINTER_DOC =
            "{\"a\":{\"b\":[10,{\"c\":true}]},\"a/b\":{\"~key\":\"esc\"},\"n\":null,\"s\":\"str\"}";

    @Test
    @DisplayName("pointer - should resolve object members, array indexes and the root document")
    void shouldResolveJsonPointer() {
        assertThat(jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a/b/0")))
                .extracting(JsonPointerResponse::found, r -> r.value().asInt(), JsonPointerResponse::valueType)
                .containsExactly(true, 10, "NUMBER");

        JsonPointerResponse object = jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a/b/1"));
        assertThat(object.found()).isTrue();
        assertThat(object.valueType()).isEqualTo("OBJECT");
        assertThat(object.pointer()).isEqualTo("/a/b/1");

        assertThat(jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a")).valueType())
                .isEqualTo("OBJECT");
        assertThat(jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a/b")).valueType())
                .isEqualTo("ARRAY");
        assertThat(jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/s")).valueType())
                .isEqualTo("STRING");
        assertThat(jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a/b/1/c")).valueType())
                .isEqualTo("BOOLEAN");
        assertThat(jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/n")).valueType())
                .isEqualTo("NULL");

        JsonPointerResponse root = jsonService.pointer(new JsonPointerRequest(POINTER_DOC, ""));
        assertThat(root.found()).isTrue();
        assertThat(root.pointer()).isEmpty();
        assertThat(root.valueType()).isEqualTo("OBJECT");
        assertThat(root.value().path("s").asText()).isEqualTo("str");
    }

    @Test
    @DisplayName("pointer - should resolve escaped ~1 to / and ~0 to ~ tokens")
    void shouldResolveEscapedPointerTokens() {
        JsonPointerResponse quoted = jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a~1b/~0key"));
        assertThat(quoted.found()).isTrue();
        assertThat(quoted.value().asText()).isEqualTo("esc");
        assertThat(quoted.valueType()).isEqualTo("STRING");
    }

    @Test
    @DisplayName("pointer - should report an absent member or index as found=false")
    void shouldReportMissingPointerTarget() {
        JsonPointerResponse missing = jsonService.pointer(
                new JsonPointerRequest(POINTER_DOC, "/a/missing"));
        assertThat(missing.found()).isFalse();
        assertThat(missing.value()).isNull();
        assertThat(missing.valueType()).isNull();

        JsonPointerResponse outOfRange = jsonService.pointer(
                new JsonPointerRequest(POINTER_DOC, "/a/b/9"));
        assertThat(outOfRange.found()).isFalse();
        assertThat(outOfRange.value()).isNull();
    }

    @Test
    @DisplayName("pointer - should reject malformed pointers with 400 BAD_REQUEST")
    void shouldRejectMalformedJsonPointer() {
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "a/b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed JSON Pointer");
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a~2b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed JSON Pointer");
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/a~")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed JSON Pointer");
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed JSON Pointer");
    }

    @Test
    @DisplayName("pointer - should reject traversal through a scalar with 400 BAD_REQUEST")
    void shouldRejectScalarTraversal() {
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/s/x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot traverse through a scalar");
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, "/n/x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot traverse through a scalar");
    }

    @Test
    @DisplayName("pointer - should reject a pointer longer than 1024 characters")
    void shouldRejectOversizedPointer() {
        String tooLong = "/" + "a".repeat(1024);
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON pointer must not exceed 1024 characters");
    }

    @Test
    @DisplayName("pointer - should reject a pointer deeper than 64 segments")
    void shouldRejectTooDeepPointer() {
        String tooDeep = "/a".repeat(65);
        assertThatThrownBy(() -> jsonService.pointer(new JsonPointerRequest(POINTER_DOC, tooDeep)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON pointer must not exceed 64 segments");

        String atLimit = "/a".repeat(64);
        assertThat(jsonService.pointer(new JsonPointerRequest("{}", atLimit)).found()).isFalse();
    }

    private static final int MAX_KEYS = 10000;
    private static final int MAX_CELLS = 1000000;

    private static String flatDocument(int keyCount) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < keyCount; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"k").append(i).append("\":").append(i);
        }
        return json.append('}').toString();
    }

    private static List<JsonPatchOperation> patchOperations(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> op("add", "/k" + i, null, String.valueOf(i)))
                .toList();
    }
}
