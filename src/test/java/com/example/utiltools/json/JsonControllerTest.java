package com.example.utiltools.json;

import com.example.utiltools.audit.AuditTestSupport;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.json.controller.JsonController;
import com.example.utiltools.text.service.TextService;
import com.example.utiltools.resilience.ResilienceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JsonController.class)
@Import({AuditTestSupport.class, AuditJournalService.class, AuditedOperations.class, CryptoService.class, DateTimeService.class,
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
