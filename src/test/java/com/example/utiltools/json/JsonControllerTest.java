package com.example.utiltools.json;

import com.example.utiltools.audit.AuditTestSupport;
import com.example.utiltools.audit.controller.AuditController;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.dto.JsonFromCsvRequest;
import com.example.utiltools.json.dto.JsonToCsvRequest;
import com.example.utiltools.json.service.JsonQueryFallbackCache;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.json.controller.JsonController;
import com.example.utiltools.text.service.TextService;
import com.example.utiltools.resilience.CircuitBreaker;
import com.example.utiltools.resilience.CircuitState;
import com.example.utiltools.resilience.ResilienceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({JsonController.class, AuditController.class})
@Import({AuditTestSupport.class, AuditJournalService.class, AuditedOperations.class, AuditReplayService.class,
        CryptoService.class, DateTimeService.class,
        JsonService.class, TextService.class, GlobalExceptionHandler.class, ResilienceConfig.class})
@TestPropertySource(properties = {"utiltools.audit.max-entries=200", "utiltools.audit.max-replay-bytes=65536"})
class JsonControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @Autowired
    private JsonQueryFallbackCache fallbackCache;

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should format JSON")
    void shouldFormatJson() throws Exception {
        String body = """
                {
                    "json": "{\\"name\\":\\"Bob\\",\\"age\\":25}",
                    "minify": false
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.formattedJson").isString())
                .andExpect(jsonPath("$.data.sizeBytes").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/validate - should return valid status for proper JSON")
    void shouldValidateJson() throws Exception {
        String body = """
                {
                    "json": "{\\"valid\\": true}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.type").value("OBJECT"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/query - should extract values with JSONPath")
    void shouldQueryJson() throws Exception {
        String body = """
                {
                    "json": "{\\"items\\": [10, 20, 30]}",
                    "jsonPath": "$.items[1]"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jsonPath").value("$.items[1]"))
                .andExpect(jsonPath("$.data.result").value(20));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/query - opt-in request is served stale when the circuit is open")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void shouldServeStaleFallbackEnvelopeWhenCircuitOpen() throws Exception {
        String optIn = """
                {
                    "json": "{\\"items\\": [10, 20, 30]}",
                    "jsonPath": "$.items[1]",
                    "allowStaleFallback": true
                }
                """;

        // Warm: a live result carries no fallback metadata at all.
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(optIn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value(20))
                .andExpect(jsonPath("$.data.staleFallback").value(nullValue()))
                .andExpect(jsonPath("$.data.cachedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.cacheAgeMillis").value(nullValue()));
        assertThat(fallbackCache.stats().size()).isEqualTo(1);

        tripCircuitBreaker();
        var resetAt = circuitBreaker.resetAt();
        var auditSize = circuitBreaker.auditTrail().recent(1024).size();

        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(optIn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jsonPath").value("$.items[1]"))
                .andExpect(jsonPath("$.data.result").value(20))
                .andExpect(jsonPath("$.data.staleFallback").value(true))
                .andExpect(jsonPath("$.data.cachedAt").isString())
                .andExpect(jsonPath("$.data.cacheAgeMillis").isNumber());

        // The degraded read leaves the breaker exactly as it was.
        assertThat(circuitBreaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(circuitBreaker.resetAt()).isEqualTo(resetAt);
        assertThat(circuitBreaker.consecutiveSuccesses()).isZero();
        assertThat(circuitBreaker.auditTrail().recent(1024).size()).isEqualTo(auditSize);
        assertThat(fallbackCache.stats().hits()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/query - circuit open without an opt-in cache hit still fails closed")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void shouldFailClosedWithCircuitOpenEnvelopeOnFallbackMiss() throws Exception {
        String warm = """
                {
                    "json": "{\\"items\\": [10, 20, 30]}",
                    "jsonPath": "$.items[1]"
                }
                """;
        String otherPath = """
                {
                    "json": "{\\"items\\": [10, 20, 30]}",
                    "jsonPath": "$.items[2]",
                    "allowStaleFallback": true
                }
                """;
        String noOptIn = """
                {
                    "json": "{\\"items\\": [10, 20, 30]}",
                    "jsonPath": "$.items[1]"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(warm))
                .andExpect(status().isOk());
        tripCircuitBreaker();

        // Opt-in but a different jsonPath: the key is absent, so the caller sees the plain 503.
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherPath))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.details.state").value("OPEN"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // Warming key but no opt-in: still the unchanged CIRCUIT_OPEN envelope.
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noOptIn))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.details.state").value("OPEN"));

        assertThat(fallbackCache.stats().size()).isEqualTo(1);
        assertThat(circuitBreaker.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should sort keys recursively when sortKeys is true")
    void shouldFormatJsonWithSortKeys() throws Exception {
        String body = """
                {
                    "json": "{\\"z\\":1,\\"a\\":{\\"d\\":4,\\"b\\":2},\\"list\\":[{\\"y\\":1,\\"x\\":2}]}",
                    "minify": true,
                    "sortKeys": true
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("JSON formatted successfully"))
                .andExpect(jsonPath("$.data.formattedJson")
                        .value("{\"a\":{\"b\":2,\"d\":4},\"list\":[{\"x\":2,\"y\":1}],\"z\":1}"))
                .andExpect(jsonPath("$.data.sizeBytes").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should keep input order when sortKeys is omitted")
    void shouldKeepInputOrderWithoutSortKeys() throws Exception {
        String body = """
                {
                    "json": "{\\"z\\":1,\\"a\\":2}",
                    "minify": true
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formattedJson").value("{\"z\":1,\"a\":2}"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should return 400 for malformed json when sortKeys is true")
    void shouldReturnBadRequestForMalformedJsonWhenSortKeysRequested() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":}",
                    "sortKeys": true
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("Invalid JSON content")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should reject blank json payload")
    void shouldRejectBlankJson() throws Exception {
        String body = """
                {
                    "json": ""
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
    @Test
    @DisplayName("POST /api/v1/utils/json/format - echoes X-Request-Id in the response envelope")
    void shouldCarryRequestIdInEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/format")
                        .header("X-Request-Id", "corr-json123456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"json": "[1,2,3]"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "corr-json123456"))
                .andExpect(jsonPath("$.requestId").value("corr-json123456"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should format nested JSON with four spaces when indent=4")
    void shouldFormatWithIndentFour() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":{\\"b\\":1}}",
                    "indent": 4
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.formattedJson").value(containsString("\n    \"a\" : {")))
                .andExpect(jsonPath("$.data.formattedJson").value(containsString("\n        \"b\" : 1")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should reject indent below one")
    void shouldRejectIndentZero() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":1}",
                    "indent": 0
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("indent must be between 1 and 8"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/format - should reject indent above eight")
    void shouldRejectIndentNine() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":1}",
                    "indent": 9
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("indent must be between 1 and 8"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/patch - should apply operations and return the audit")
    void shouldPatchJson() throws Exception {
        String body = """
                {
                    "json": "{\\"name\\":\\"Alice\\",\\"age\\":30}",
                    "operations": [
                        {"op": "replace", "path": "/name", "value": "Bob"},
                        {"op": "add", "path": "/city", "value": "Paris"},
                        {"op": "copy", "from": "/age", "path": "/years"}
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resultJson.name").value("Bob"))
                .andExpect(jsonPath("$.data.resultJson.city").value("Paris"))
                .andExpect(jsonPath("$.data.resultJson.years").value(30))
                .andExpect(jsonPath("$.data.operationsApplied").value(3))
                .andExpect(jsonPath("$.data.operationsRolledBack").value(0))
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.audit.length()").value(3))
                .andExpect(jsonPath("$.data.audit[0].index").value(0))
                .andExpect(jsonPath("$.data.audit[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.data.audit[2].status").value("APPLIED"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/patch - should return 422 PATCH_ROLLBACK with the audit")
    void shouldReturn422WithAuditDetailsOnRollback() throws Exception {
        String body = """
                {
                    "json": "{\\"name\\":\\"Alice\\"}",
                    "operations": [
                        {"op": "replace", "path": "/name", "value": "Bob"},
                        {"op": "remove", "path": "/missing"}
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("PATCH_ROLLBACK"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("operation 1")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/missing")))
                .andExpect(jsonPath("$.details.failedOperationIndex").value(1))
                .andExpect(jsonPath("$.details.failedPath").value("/missing"))
                .andExpect(jsonPath("$.details.operationsRolledBack").value(1))
                .andExpect(jsonPath("$.details.reason").value("Missing path: /missing"))
                .andExpect(jsonPath("$.details.audit.length()").value(2))
                .andExpect(jsonPath("$.details.audit[0].status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.details.audit[1].status").value("FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/patch - should reject an empty operations list")
    void shouldRejectEmptyOperationsList() throws Exception {
        String body = """
                {
                    "json": "{\\"name\\":\\"Alice\\"}",
                    "operations": []
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/patch - should reject 101 operations")
    void shouldRejectPatchWith101Operations() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBodyWithOperations(101, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.details[0]").value("operations must contain at most 100 entries"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/patch - should reject 101 operations on a dry run")
    void shouldRejectDryRunPatchWith101Operations() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBodyWithOperations(101, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.details[0]").value("operations must contain at most 100 entries"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/patch - should accept exactly 100 operations")
    void shouldAcceptPatchWithExactly100Operations() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBodyWithOperations(100, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.operationsApplied").value(100))
                .andExpect(jsonPath("$.data.operationsRolledBack").value(0))
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.audit.length()").value(100))
                .andExpect(jsonPath("$.data.audit[0].index").value(0))
                .andExpect(jsonPath("$.data.audit[99].index").value(99))
                .andExpect(jsonPath("$.data.audit[99].status").value("APPLIED"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/flatten - should return dot-path keys with keyCount and maxDepth")
    void shouldFlattenJson() throws Exception {
        String body = """
                {
                    "json": "{\\"name\\":\\"Alice\\",\\"address\\":{\\"city\\":\\"Paris\\"},\\"tags\\":[\\"a\\"]}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/flatten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resultJson.name").value("Alice"))
                .andExpect(jsonPath("$.data.resultJson['address.city']").value("Paris"))
                .andExpect(jsonPath("$.data.resultJson.tags[0]").value("a"))
                .andExpect(jsonPath("$.data.keyCount").value(3))
                .andExpect(jsonPath("$.data.maxDepth").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/unflatten - should rebuild nested JSON from dot-path keys")
    void shouldUnflattenJson() throws Exception {
        String body = """
                {
                    "json": "{\\"a.b\\":1,\\"a.c\\":2}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/unflatten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resultJson.a.b").value(1))
                .andExpect(jsonPath("$.data.resultJson.a.c").value(2))
                .andExpect(jsonPath("$.data.keyCount").value(2))
                .andExpect(jsonPath("$.data.maxDepth").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/unflatten - conflicting paths return 400 BAD_REQUEST")
    void shouldRejectUnflattenConflictWithBadRequest() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":1,\\"a.b\\":2}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/unflatten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("conflicting path")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/flatten - blank json returns 400 VALIDATION_ERROR")
    void shouldRejectBlankJsonOnFlatten() throws Exception {
        String body = """
                {
                    "json": ""
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/flatten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/flatten and unflatten - journalled as JSON_FLATTEN / JSON_UNFLATTEN and replay VERIFIED")
    void shouldJournalAndVerifyFlattenAndUnflatten() throws Exception {
        String flattenBody = """
                {
                    "json": "{\\"a\\":{\\"b\\":1}}"
                }
                """;
        String unflattenBody = """
                {
                    "json": "{\\"a.b\\":1}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/flatten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flattenBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/utils/json/unflatten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unflattenBody))
                .andExpect(status().isOk());

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_FLATTEN')]").exists())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_UNFLATTEN')]").exists())
                .andReturn().getResponse().getContentAsString();

        for (String operation : new String[]{"JSON_FLATTEN", "JSON_UNFLATTEN"}) {
            String entryId = entryIdOf(entries, operation);
            mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entryId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"));
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/merge-patch - should merge, delete null members and count removals")
    void shouldMergePatchReturnSuccessEnvelope() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":1,\\"b\\":{\\"c\\":2,\\"d\\":3}}",
                    "patch": "{\\"b\\":{\\"c\\":null,\\"e\\":4},\\"f\\":[1,2]}"
                }
                """;

        String response = mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.removedKeys").value(1))
                .andExpect(jsonPath("$.data.sizeBytes").value(35))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = new ObjectMapper().readTree(response).path("data");
        assertThat(data.path("resultJson").toString()).isEqualTo("{\"a\":1,\"b\":{\"d\":3,\"e\":4},\"f\":[1,2]}");
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/merge-patch - non-object patch replaces the target document")
    void shouldReplaceTargetWithNonObjectMergePatch() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":1}",
                    "patch": "7"
                }
                """;

        String response = mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.removedKeys").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = new ObjectMapper().readTree(response).path("data");
        assertThat(data.path("resultJson").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/merge-patch - malformed json or patch returns 400 BAD_REQUEST")
    void shouldMergePatchReturnBadRequestForMalformedJson() throws Exception {
        String badJson = """
                {
                    "json": "{not json",
                    "patch": "{}"
                }
                """;
        String badPatch = """
                {
                    "json": "{}",
                    "patch": "[1,"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("Invalid JSON")));
        mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("Invalid JSON")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/merge-patch - blank fields return 400 VALIDATION_ERROR")
    void shouldMergePatchReturnValidationErrorForBlankFields() throws Exception {
        String blankJson = """
                {
                    "json": "",
                    "patch": "{}"
                }
                """;
        String blankPatch = """
                {
                    "json": "{}",
                    "patch": "  "
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]", containsString("json content is required")));
        mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]", containsString("patch content is required")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/merge-patch - journalled as JSON_MERGE_PATCH and replay VERIFIED")
    void shouldMergePatchBeJournalledAndReplayVerified() throws Exception {
        String body = """
                {
                    "json": "{\\"a\\":1,\\"b\\":{\\"c\\":2,\\"d\\":3}}",
                    "patch": "{\\"b\\":{\\"c\\":null,\\"e\\":4},\\"f\\":[1,2]}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/utils/json/merge-patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"json\":\"{\\\"a\\\":1}\",\"patch\":\"{\\\"b\\\":2}\"}"))
                .andExpect(status().isOk());

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_MERGE_PATCH')]").exists())
                .andReturn().getResponse().getContentAsString();

        String entryId = entryIdOf(entries, "JSON_MERGE_PATCH");
        int entryCount = new ObjectMapper().readTree(entries).path("data").size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"));

        // Replay must not append a second journal entry for the same request.
        String afterReplay = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode afterEntries = new ObjectMapper().readTree(afterReplay).path("data");
        assertThat(afterEntries.size()).isEqualTo(entryCount);
        JsonNode replayedEntry = null;
        for (JsonNode entry : afterEntries) {
            if (entryId.equals(entry.path("id").asText())) {
                replayedEntry = entry;
            }
        }
        assertThat(replayedEntry).isNotNull();
        assertThat(replayedEntry.path("operation").asText()).isEqualTo("JSON_MERGE_PATCH");
        assertThat(replayedEntry.path("state").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/diff - should report nested differences and journal JSON_DIFF")
    void shouldDiffTwoJsonDocuments() throws Exception {
        String body = """
                {
                    "jsonA": "{\\"a\\":1,\\"b\\":{\\"c\\":2,\\"d\\":[1,2,3]},\\"e\\":true}",
                    "jsonB": "{\\"a\\":1,\\"b\\":{\\"c\\":5,\\"d\\":[1,2]},\\"f\\":true}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("JSON documents diffed successfully"))
                .andExpect(jsonPath("$.data.added[0]").value("f"))
                .andExpect(jsonPath("$.data.removed[0]").value("b.d[2]"))
                .andExpect(jsonPath("$.data.removed[1]").value("e"))
                .andExpect(jsonPath("$.data.changed['b.c'].changeType").value("CHANGED"))
                .andExpect(jsonPath("$.data.changed['b.c'].oldValue").value("2"))
                .andExpect(jsonPath("$.data.changed['b.c'].newValue").value("5"))
                .andExpect(jsonPath("$.data.changed['f'].changeType").value("ADDED"))
                .andExpect(jsonPath("$.data.summary").value("1 added, 2 removed, 1 changed"));

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_DIFF')]").exists())
                .andReturn().getResponse().getContentAsString();
        String entryId = entryIdOf(entries, "JSON_DIFF");
        for (JsonNode entry : new ObjectMapper().readTree(entries).path("data")) {
            if (entryId.equals(entry.path("id").asText())) {
                assertThat(entry.path("state").asText()).isEqualTo("SUCCEEDED");
            }
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/diff - journalled as JSON_DIFF and replay VERIFIED")
    void shouldJournalJsonDiffAndReplayVerified() throws Exception {
        String body = """
                {
                    "jsonA": "{\\"a\\":[1,{\\"b\\":2}]}",
                    "jsonB": "{\\"a\\":[1,{\\"b\\":3}],\\"c\\":null}"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/json/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String entryId = entryIdOf(entries, "JSON_DIFF");
        int entryCount = new ObjectMapper().readTree(entries).path("data").size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"));

        JsonNode afterEntries = new ObjectMapper()
                .readTree(mockMvc.perform(get("/api/v1/utils/audit/entries"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("data");
        assertThat(afterEntries.size()).isEqualTo(entryCount);
        for (JsonNode entry : afterEntries) {
            if (entryId.equals(entry.path("id").asText())) {
                assertThat(entry.path("operation").asText()).isEqualTo("JSON_DIFF");
                assertThat(entry.path("state").asText()).isEqualTo("SUCCEEDED");
            }
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/diff - should return 400 for malformed jsonA")
    void shouldReturnBadRequestForMalformedJsonAOnDiff() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonA\": \"{\", \"jsonB\": \"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("jsonA")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/diff - should reject blank fields")
    void shouldRejectBlankJsonAOnDiff() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonA\": \"\", \"jsonB\": \"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]", containsString("jsonA content is required")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/to-csv - should export an array of flat objects as RFC 4180 CSV")
    void shouldConvertJsonArrayToCsv() throws Exception {
        String payload = "[{\"a\":\"x,y\",\"b\":\"say \\\"hi\\\"\",\"c\":\"l1\\nl2\",\"n\":null}]";

        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rowCount").value(1))
                .andExpect(jsonPath("$.data.columnCount").value(4))
                .andExpect(jsonPath("$.data.headers[0]").value("a"))
                .andExpect(jsonPath("$.data.headers[3]").value("n"))
                .andExpect(jsonPath("$.data.csv")
                        .value("a,b,c,n\r\n\"x,y\",\"say \"\"hi\"\"\",\"l1\nl2\","))
                .andExpect(jsonPath("$.data.sizeBytes").isNumber());

        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csv").value(""))
                .andExpect(jsonPath("$.data.rowCount").value(0))
                .andExpect(jsonPath("$.data.columnCount").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/to-csv - should emit tab separated output for a tab delimiter")
    void shouldEmitTabSeparatedCsv() throws Exception {
        String payload = "[{\"a\":\"1\",\"b\":\"2\"}]";

        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", payload, "delimiter", "\\t")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csv").value("a\tb\r\n1\t2"));

        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", payload, "delimiter", "\t")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csv").value("a\tb\r\n1\t2"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/from-csv - should import quoted CSV, TSV and headerless input")
    void shouldConvertCsvToJson() throws Exception {
        String csv = "\"a,b\",c\r\n1,\"say \"\"hi\"\"\"\n2,\"l1\nl2\"";
        String body = requestBody("csv", csv, "hasHeader", true);

        String response = mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rowCount").value(2))
                .andExpect(jsonPath("$.data.columnCount").value(2))
                .andExpect(jsonPath("$.data.columns[0]").value("a,b"))
                .andExpect(jsonPath("$.data.columns[1]").value("c"))
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = new ObjectMapper().readTree(response).path("data").path("resultJson");
        assertThat(rows.get(0).path("a,b").asText()).isEqualTo("1");
        assertThat(rows.get(0).path("c").asText()).isEqualTo("say \"hi\"");
        assertThat(rows.get(1).path("c").asText()).isEqualTo("l1\nl2");

        mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csv", "a\tb\r\n1\t2", "delimiter", "\t")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultJson[0].b").value("2"));

        mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csv", "1,2\r\n3,4", "hasHeader", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.columns[0]").value("column1"))
                .andExpect(jsonPath("$.data.columns[1]").value("column2"))
                .andExpect(jsonPath("$.data.resultJson[1].column2").value("4"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/to-csv|from-csv - should return the 400 envelope for invalid payloads")
    void shouldReturnBadRequestEnvelopeForInvalidCsvPayloads() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("json content must be an array of objects"));

        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "[{\"a\":{\"b\":1}}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("nested values are not supported in CSV export"));

        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "[{\"a\":1}]", "delimiter", "xx")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("delimiter must be a single character"));

        mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csv", "\"a,b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("unterminated quoted field"));

        mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csv", "a,b,a\r\n1,2,3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("duplicate header name: a"));

        mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csv", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]", containsString("csv content is required")));

        mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csv", "a".repeat(1000001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]", containsString("must be at most 1000000 characters")));

        assertThat(new JsonToCsvRequest("[]", null).json()).isNotBlank();
        assertThat(new JsonFromCsvRequest("a", null, null).csv()).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/to-csv|from-csv - journalled and replay VERIFIED")
    void shouldJournalAndReplayCsvConversions() throws Exception {
        String payload = "[{\"a\":\"x,y\",\"b\":\"say \\\"hi\\\"\"}]";
        String csv = "a,b\r\n\"x,y\",\"say \"\"hi\"\"\"";

        mockMvc.perform(post("/api/v1/utils/json/to-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers[1]").value("b"));
        mockMvc.perform(post("/api/v1/utils/json/from-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csv", csv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultJson[0].a").value("x,y"));

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_TO_CSV')]").exists())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_FROM_CSV')]").exists())
                .andReturn().getResponse().getContentAsString();

        String toCsvId = entryIdOf(entries, "JSON_TO_CSV");
        String fromCsvId = entryIdOf(entries, "JSON_FROM_CSV");
        int entryCount = new ObjectMapper().readTree(entries).path("data").size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", toCsvId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"));
        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", fromCsvId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"));

        // Replay must not append a second journal entry for the same request.
        String afterReplay = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode afterEntries = new ObjectMapper().readTree(afterReplay).path("data");
        assertThat(afterEntries.size()).isEqualTo(entryCount);
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/csv-to-json - should convert CSV with delimiter and header mapping")
    void shouldConvertCsvToJsonViaCsvToJsonEndpoint() throws Exception {
        String response = mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", "name,age\nAlice,30\nBob,25")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rowCount").value(2))
                .andExpect(jsonPath("$.data.columnCount").value(2))
                .andExpect(jsonPath("$.data.headers[0]").value("name"))
                .andExpect(jsonPath("$.data.headers[1]").value("age"))
                .andExpect(jsonPath("$.data.resultJson[0].name").value("Alice"))
                .andExpect(jsonPath("$.data.resultJson[0].age").value("30"))
                .andExpect(jsonPath("$.data.resultJson[1].name").value("Bob"))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = new ObjectMapper().readTree(response).path("data");
        assertThat(data.path("resultJson").isArray()).isTrue();
        assertThat(data.path("resultJson").get(1).path("age").asText()).isEqualTo("25");

        // A non-comma delimiter and a literal tab are both honoured.
        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", "a;b\r\n1;2", "delimiter", ";")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers[1]").value("b"))
                .andExpect(jsonPath("$.data.resultJson[0].b").value("2"));

        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", "a\tb\r\n1\t2", "delimiter", "\t")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultJson[0].b").value("2"));

        // Quoted fields keep embedded delimiters and newlines, a BOM is tolerated and short rows
        // are padded with empty cells.
        String quoted = "\uFEFF\"a,b\",c\n1,\"say \"\"hi\"\"\"\n2,\"l1\nl2\"\n3";
        String quotedResponse = mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", quoted)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowCount").value(3))
                .andExpect(jsonPath("$.data.columnCount").value(2))
                .andExpect(jsonPath("$.data.headers[0]").value("a,b"))
                .andExpect(jsonPath("$.data.headers[1]").value("c"))
                .andExpect(jsonPath("$.data.resultJson[0].c").value("say \"hi\""))
                .andExpect(jsonPath("$.data.resultJson[1].c").value("l1\nl2"))
                .andExpect(jsonPath("$.data.resultJson[2].c").value(""))
                .andReturn().getResponse().getContentAsString();
        assertThat(new ObjectMapper().readTree(quotedResponse).path("data")
                .path("resultJson").get(0).path("a,b").asText()).isEqualTo("1");

        // headerMapping renames the header and every row key.
        String mappingBody = "{\"csvContent\":\"name,age\\nAlice,30\","
                + "\"headerMapping\":{\"name\":\"fullName\",\"age\":\"years\"}}";
        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers[0]").value("fullName"))
                .andExpect(jsonPath("$.data.headers[1]").value("years"))
                .andExpect(jsonPath("$.data.resultJson[0].fullName").value("Alice"))
                .andExpect(jsonPath("$.data.resultJson[0].years").value("30"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/csv-to-json - should return 400 for malformed CSV")
    void shouldRejectMalformedCsvWith400() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", "\"a,b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("unterminated quoted field"));

        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", "a,\"b\"x,c")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("unexpected character after closing quote"));

        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", "a,b,a\r\n1,2,3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("duplicate header name: a"));

        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"csvContent\":\"a,b\\n1,2\",\"headerMapping\":{\"c\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("headerMapping key 'c' does not match any header"));

        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]", containsString("csvContent is required")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/csv-to-json - journalled as JSON_CSV_TO_JSON and replay VERIFIED")
    void shouldJournalCsvToJsonAndReplayVerified() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/csv-to-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("csvContent", "name,age\r\nAlice,30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultJson[0].name").value("Alice"));

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_CSV_TO_JSON')]").exists())
                .andReturn().getResponse().getContentAsString();
        String entryId = entryIdOf(entries, "JSON_CSV_TO_JSON");
        int entryCount = new ObjectMapper().readTree(entries).path("data").size();

        String replay = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(new ObjectMapper().readTree(replay).path("data").path("result")
                .path("resultJson").get(0).path("age").asText()).isEqualTo("30");

        String afterReplay = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(new ObjectMapper().readTree(afterReplay).path("data").size()).isEqualTo(entryCount);
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/to-ndjson - should export an array as NDJSON")
    void shouldConvertJsonArrayToNdjson() throws Exception {
        String body = requestBody("json", "[{\"a\":1},{\"b\":2}]");

        String response = mockMvc.perform(post("/api/v1/utils/json/to-ndjson")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.lineCount").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = new ObjectMapper().readTree(response).path("data");
        String expected = "{\"a\":1}\n{\"b\":2}";
        assertThat(data.path("ndjson").asText()).isEqualTo(expected);
        assertThat(data.path("sizeBytes").asInt())
                .isEqualTo(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/from-ndjson - should import NDJSON with blank lines")
    void shouldConvertNdjsonToJsonArray() throws Exception {
        String body = requestBody("ndjson", "{\"a\":1}\n\n{\"b\":2}\n");

        mockMvc.perform(post("/api/v1/utils/json/from-ndjson")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordCount").value(2))
                .andExpect(jsonPath("$.data.resultJson[0].a").value(1))
                .andExpect(jsonPath("$.data.resultJson[1].b").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/from-ndjson|to-ndjson - should return 400 with the physical line number")
    void shouldReturnBadRequestWithPhysicalLineNumber() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/from-ndjson")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("ndjson", "{\"a\":1}\n\n{not json}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("Invalid JSON on line 3")));

        mockMvc.perform(post("/api/v1/utils/json/to-ndjson")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("json content must be an array")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/to-ndjson|from-ndjson - journalled and replay VERIFIED")
    void shouldJournalAndReplayNdjsonConversions() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/to-ndjson")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "[{\"a\":1},{\"b\":2}]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineCount").value(2));
        mockMvc.perform(post("/api/v1/utils/json/from-ndjson")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("ndjson", "{\"a\":1}\n{\"b\":2}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordCount").value(2));

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_TO_NDJSON')]").exists())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_FROM_NDJSON')]").exists())
                .andReturn().getResponse().getContentAsString();

        String toNdjsonId = entryIdOf(entries, "JSON_TO_NDJSON");
        String fromNdjsonId = entryIdOf(entries, "JSON_FROM_NDJSON");
        int entryCount = new ObjectMapper().readTree(entries).path("data").size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", toNdjsonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"));
        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", fromNdjsonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"));

        String afterReplay = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode afterEntries = new ObjectMapper().readTree(afterReplay).path("data");
        assertThat(afterEntries.size()).isEqualTo(entryCount);
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/from-ndjson - should reject more than 10000 records")
    void shouldEnforceNdjsonRecordBoundThroughEndpoint() throws Exception {
        StringBuilder ndjson = new StringBuilder("0");
        for (int i = 1; i <= 10000; i++) {
            ndjson.append('\n').append(i);
        }

        mockMvc.perform(post("/api/v1/utils/json/from-ndjson")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("ndjson", ndjson.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("records must not exceed 10000")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/pointer - should resolve a node and journal JSON_POINTER")
    void shouldResolveJsonPointer() throws Exception {
        String body = requestBody("json", "{\"a\":{\"b\":[1,{\"c\":\"x\"}]},\"a/b\":{\"~key\":7}}",
                "pointer", "/a/b/1/c");

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("JSON pointer resolved successfully"))
                .andExpect(jsonPath("$.data.pointer").value("/a/b/1/c"))
                .andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.value").value("x"))
                .andExpect(jsonPath("$.data.valueType").value("STRING"))
                .andExpect(jsonPath("$.requestId").exists());

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":1}", "pointer", "/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value(1))
                .andExpect(jsonPath("$.data.valueType").value("NUMBER"));

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":true}", "pointer", "/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value(true))
                .andExpect(jsonPath("$.data.valueType").value("BOOLEAN"));

        // Escaping and the empty (whole document) pointer.
        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a/b\":{\"~key\":7}}", "pointer", "/a~1b/~0key")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value(7));

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":null}", "pointer", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.valueType").value("OBJECT"));

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.operation == 'JSON_POINTER')]").exists())
                .andReturn().getResponse().getContentAsString();
        String entryId = entryIdOf(entries, "JSON_POINTER");
        for (JsonNode entry : new ObjectMapper().readTree(entries).path("data")) {
            if (entryId.equals(entry.path("id").asText())) {
                assertThat(entry.path("state").asText()).isEqualTo("SUCCEEDED");
            }
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/pointer - absent member is 200 with found=false")
    void shouldReturnNotFoundFlagForAbsentPointerTarget() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":[1]}", "pointer", "/a/5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.found").value(false))
                .andExpect(jsonPath("$.data.value").doesNotExist())
                .andExpect(jsonPath("$.data.valueType").doesNotExist())
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/pointer - should return 400 for malformed and unbounded pointers")
    void shouldRejectBadJsonPointer() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":1}", "pointer", "a/b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("Malformed JSON Pointer")))
                .andExpect(jsonPath("$.requestId").exists());

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":1}", "pointer", "/a~2b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"s\":\"x\"}", "pointer", "/s/x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("scalar")));

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{}", "pointer", "/" + "a".repeat(1024))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("1024 characters")));

        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{}", "pointer", "/a".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("64 segments")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/pointer - should reject a null pointer")
    void shouldRejectNullPointer() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"json\": \"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]", containsString("pointer must not be null")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/json/pointer - journalled as JSON_POINTER and replay VERIFIED")
    void shouldJournalJsonPointerAndReplayVerified() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/pointer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("json", "{\"a\":{\"b\":[1,2,3]}}", "pointer", "/a/b/2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value(3));

        String entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String entryId = entryIdOf(entries, "JSON_POINTER");
        int entryCount = new ObjectMapper().readTree(entries).path("data").size();

        mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", entryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.result.value").value(3))
                .andExpect(jsonPath("$.data.result.valueType").value("NUMBER"));

        JsonNode afterEntries = new ObjectMapper()
                .readTree(mockMvc.perform(get("/api/v1/utils/audit/entries"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("data");
        assertThat(afterEntries.size()).isEqualTo(entryCount);
        for (JsonNode entry : afterEntries) {
            if (entryId.equals(entry.path("id").asText())) {
                assertThat(entry.path("operation").asText()).isEqualTo("JSON_POINTER");
                assertThat(entry.path("state").asText()).isEqualTo("SUCCEEDED");
            }
        }
    }

    private void tripCircuitBreaker() {
        for (int i = 0; i < 10 && circuitBreaker.state() != CircuitState.OPEN; i++) {
            try {
                circuitBreaker.execute(() -> {
                    throw new IllegalStateException("forced failure");
                });
            } catch (RuntimeException expected) {
                // the delegate's own exception is rethrown until the threshold opens the circuit
            }
        }
        assertThat(circuitBreaker.state()).isEqualTo(CircuitState.OPEN);
    }

    private static String requestBody(String field, String value) throws Exception {
        return new ObjectMapper().createObjectNode().put(field, value).toString();
    }

    private static String requestBody(String field, String value, String extraField, Object extraValue)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.createObjectNode();
        node.put(field, value);
        node.set(extraField, mapper.valueToTree(extraValue));
        return node.toString();
    }

    private static String entryIdOf(String entriesJson, String operation) throws Exception {
        JsonNode entries = new ObjectMapper().readTree(entriesJson).path("data");
        for (JsonNode entry : entries) {
            if (operation.equals(entry.path("operation").asText())) {
                return entry.path("id").asText();
            }
        }
        throw new AssertionError("no audit entry for " + operation + " in " + entriesJson);
    }

    private static String patchBodyWithOperations(int count, boolean dryRun) {
        StringBuilder operations = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                operations.append(',');
            }
            operations.append("{\"op\": \"add\", \"path\": \"/k").append(i)
                    .append("\", \"value\": ").append(i).append('}');
        }
        return "{\"json\": \"{\\\"a\\\":1}\", \"operations\": [" + operations + "]"
                + (dryRun ? ", \"dryRun\": true" : "") + "}";
    }
}
