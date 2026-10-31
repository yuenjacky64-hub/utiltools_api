package com.example.utiltools.json;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.json.controller.JsonController;
import com.example.utiltools.json.service.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JsonController.class)
@Import({JsonService.class, GlobalExceptionHandler.class})
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
}
