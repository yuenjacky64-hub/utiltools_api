package com.example.utiltools.json;

import com.example.utiltools.json.dto.JsonFlattenRequest;
import com.example.utiltools.json.dto.JsonFlattenResponse;
import com.example.utiltools.json.dto.JsonFormatRequest;
import com.example.utiltools.json.dto.JsonFormatResponse;
import com.example.utiltools.json.dto.JsonPatchOperation;
import com.example.utiltools.json.dto.JsonPatchResponse;
import com.example.utiltools.json.dto.JsonPatchRequest;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
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
        JsonFormatRequest request = new JsonFormatRequest(compact, false, 2);
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
        JsonFormatRequest request = new JsonFormatRequest(formatted, true, null);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).isEqualTo("{\"name\":\"Alice\",\"age\":30}");
    }

    @Test
    @DisplayName("Should indent nested JSON with the supplied width of four spaces")
    void shouldIndentNestedOutputWithWidthFour() {
        JsonFormatRequest request = new JsonFormatRequest("{\"a\":{\"b\":1}}", false, 4);
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
        JsonFormatRequest request = new JsonFormatRequest(json, false, null);
        JsonFormatResponse response = jsonService.format(request);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        assertThat(response.formattedJson())
                .isEqualTo(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    @Test
    @DisplayName("Should ignore indent when minify is true")
    void shouldIgnoreIndentWhenMinifyTrue() {
        JsonFormatRequest request = new JsonFormatRequest("{\"a\":{\"b\":1}}", true, 8);
        JsonFormatResponse response = jsonService.format(request);

        assertThat(response.formattedJson()).isEqualTo("{\"a\":{\"b\":1}}");
        assertThat(response.formattedJson()).doesNotContain("\n");
    }

    @Test
    @DisplayName("Should compute sizeBytes from the final formatted output")
    void shouldComputeSizeBytesFromFormattedOutput() {
        String json = "{\"a\":{\"b\":1}}";
        JsonFormatResponse indented = jsonService.format(new JsonFormatRequest(json, false, 4));
        JsonFormatResponse minified = jsonService.format(new JsonFormatRequest(json, true, null));

        assertThat(indented.sizeBytes())
                .isEqualTo(indented.formattedJson().getBytes(StandardCharsets.UTF_8).length);
        assertThat(indented.sizeBytes()).isNotEqualTo(minified.sizeBytes());
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
