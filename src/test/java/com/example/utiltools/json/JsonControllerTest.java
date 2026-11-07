package com.example.utiltools.json;

import com.example.utiltools.audit.AuditTestSupport;
import com.example.utiltools.audit.controller.AuditController;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.json.controller.JsonController;
import com.example.utiltools.text.service.TextService;
import com.example.utiltools.resilience.ResilienceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
