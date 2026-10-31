package com.example.utiltools.datetime;

import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.datetime.controller.DateTimeController;
import com.example.utiltools.datetime.service.DateTimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DateTimeController.class)
@Import({DateTimeService.class, GlobalExceptionHandler.class})
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
    @DisplayName("POST /api/v1/utils/datetime/shift - should clamp month-end and return the shift envelope")
    void shouldShiftDateTime() throws Exception {
        String body = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 1,
                    "unit": "MONTHS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("DateTime shifted successfully"))
                .andExpect(jsonPath("$.data.formattedTarget").value("2026-02-28T12:00:00Z"))
                .andExpect(jsonPath("$.data.isoUtc").value("2026-02-28T12:00:00Z"))
                .andExpect(jsonPath("$.data.epochMillis").value(1772280000000L))
                .andExpect(jsonPath("$.data.epochSeconds").value(1772280000L))
                .andExpect(jsonPath("$.data.targetTimeZone").value("UTC"))
                .andExpect(jsonPath("$.data.appliedAmount").value(1))
                .andExpect(jsonPath("$.data.appliedUnit").value("MONTHS"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/shift - should reject amount 0 with 400 VALIDATION_ERROR")
    void shouldRejectZeroAmountWithValidationError() throws Exception {
        String body = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 0,
                    "unit": "MONTHS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/shift - should reject an unsupported unit and accept lower-case units")
    void shouldRejectUnsupportedUnit() throws Exception {
        String unknownUnit = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 1,
                    "unit": "FORTNIGHTS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownUnit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        String missingAmount = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "unit": "MONTHS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingAmount))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        String lowerCaseUnit = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 1,
                    "unit": "months",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lowerCaseUnit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedUnit").value("MONTHS"))
                .andExpect(jsonPath("$.data.formattedTarget").value("2026-02-28T12:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/shift - should return 400 BAD_REQUEST for an unknown timezone")
    void shouldReturnBadRequestForUnknownTimeZoneOnShift() throws Exception {
        String body = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 1,
                    "unit": "MONTHS",
                    "targetTimeZone": "Invalid/Zone"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unknown or invalid timezone")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/shift - should return 400 BAD_REQUEST for an invalid custom pattern")
    void shouldReturnBadRequestForInvalidPatternOnShift() throws Exception {
        String body = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 1,
                    "unit": "MONTHS",
                    "targetTimeZone": "UTC",
                    "customPattern": "yyyy-MM-dd 'unterminated"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid date-time pattern")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/shift - should return 400 BAD_REQUEST for malformed JSON")
    void shouldRejectMalformedJsonOnShift() throws Exception {
        String body = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 1,
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
