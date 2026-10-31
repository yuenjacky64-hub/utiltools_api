package com.example.utiltools.json;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.json.controller.JsonController;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.resilience.ResilienceConfig;
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
@Import({JsonService.class, GlobalExceptionHandler.class, ResilienceConfig.class})
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
}
