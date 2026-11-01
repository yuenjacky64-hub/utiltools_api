package com.example.utiltools.json;

import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.json.controller.JsonController;
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
@Import({AuditJournalService.class, AuditedOperations.class, CryptoService.class, DateTimeService.class,
        JsonService.class, GlobalExceptionHandler.class})
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
}
