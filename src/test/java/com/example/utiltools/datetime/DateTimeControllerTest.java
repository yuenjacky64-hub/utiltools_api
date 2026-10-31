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
    @DisplayName("POST /api/v1/utils/datetime/convert - should treat a blank inputFormat as auto-detect")
    void shouldTreatBlankInputFormatAsAutoDetect() throws Exception {
        String blankBody = """
                {
                    "input": "1000000000",
                    "inputFormat": "   ",
                    "targetTimeZone": "UTC"
                }
                """;
        String omittedBody = """
                {
                    "input": "1000000000",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.epochMillis").value(1000000000000L));

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(omittedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.epochMillis").value(1000000000000L));
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
    @DisplayName("POST /api/v1/utils/datetime/convert - should honor explicit EPOCH_SECONDS")
    void shouldHonorExplicitEpochSeconds() throws Exception {
        String body = """
                {
                    "input": "1730335200",
                    "inputFormat": "EPOCH_SECONDS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.epochMillis").value(1730335200000L))
                .andExpect(jsonPath("$.data.epochSeconds").value(1730335200L));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/convert - should honor explicit EPOCH_MILLIS")
    void shouldHonorExplicitEpochMillis() throws Exception {
        String body = """
                {
                    "input": "1730335200000",
                    "inputFormat": "EPOCH_MILLIS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.epochMillis").value(1730335200000L));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/convert - should reject ISO-8601 input under forced EPOCH_MILLIS")
    void shouldRejectIsoInputUnderForcedEpochMillis() throws Exception {
        String body = """
                {
                    "input": "2024-10-31T09:40:00+09:00",
                    "inputFormat": "EPOCH_MILLIS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not match inputFormat EPOCH_MILLIS")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/convert - should reject unknown inputFormat with allowed values")
    void shouldRejectUnknownInputFormat() throws Exception {
        String body = """
                {
                    "input": "1730335200000",
                    "inputFormat": "BOGUS"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("EPOCH_SECONDS")))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("EPOCH_MILLIS")))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("ISO_8601")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/convert - should answer 400 (not 500) for out-of-range epoch seconds")
    void shouldRejectOutOfRangeEpochSecondsWith400() throws Exception {
        String body = """
                {
                    "input": "9223372036854775807",
                    "inputFormat": "EPOCH_SECONDS"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
