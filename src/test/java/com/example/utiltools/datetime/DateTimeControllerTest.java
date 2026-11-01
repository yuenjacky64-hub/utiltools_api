package com.example.utiltools.datetime;

import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.datetime.controller.DateTimeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DateTimeController.class)
@Import({AuditJournalService.class, AuditedOperations.class, CryptoService.class, DateTimeService.class,
        JsonService.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {"utiltools.audit.max-entries=200", "utiltools.audit.max-replay-bytes=65536"})
class DateTimeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/v1/utils/datetime/convert - should convert epoch millis to Tokyo time")
    void shouldConvertDateTime() throws Exception {
        String body = """
                {
                    "input": "1730335200000",
                    "targetTimeZone": "Asia/Tokyo",
                    "customPattern": "yyyy-MM-dd HH:mm:ss"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.epochMillis").value(1730335200000L))
                .andExpect(jsonPath("$.data.isoUtc").value("2024-10-31T00:40:00Z"))
                .andExpect(jsonPath("$.data.targetTimeZone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.data.formattedTarget").value("2024-10-31 09:40:00"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/diff - should calculate duration between two dates")
    void shouldCalculateDifference() throws Exception {
        String body = """
                {
                    "start": "2026-01-01T00:00:00Z",
                    "end": "2026-01-03T12:30:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.days").value(2))
                .andExpect(jsonPath("$.data.hours").value(60))
                .andExpect(jsonPath("$.data.minutes").value(3630))
                .andExpect(jsonPath("$.data.humanReadable").value("2 days 12 hours 30 minutes 0 seconds"));
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/current - should return current time")
    void shouldReturnCurrentTime() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/current")
                        .param("timeZone", "UTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.timeZone").value("UTC"))
                .andExpect(jsonPath("$.data.epochMillis").isNumber())
                .andExpect(jsonPath("$.data.utc").isString());
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/convert - should reject blank input with 400 validation error")
    void shouldRejectBlankInput() throws Exception {
        String body = """
                {
                    "input": ""
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
    @Test
    @DisplayName("GET /api/v1/utils/datetime/current - echoes X-Request-Id in the response envelope")
    void shouldCarryRequestIdInEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/current")
                        .header("X-Request-Id", "corr-datetime01"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "corr-datetime01"))
                .andExpect(jsonPath("$.requestId").value("corr-datetime01"));
    }
}
