package com.example.utiltools.datetime;

import com.example.utiltools.audit.AuditTestSupport;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.resilience.ResilienceConfig;
import com.example.utiltools.datetime.controller.DateTimeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DateTimeController.class)
@Import({AuditTestSupport.class, AuditJournalService.class, AuditedOperations.class, CryptoService.class, DateTimeService.class,
        JsonService.class, ResilienceConfig.class, GlobalExceptionHandler.class})
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
    @DisplayName("GET /api/v1/utils/datetime/timezones - should list filtered timezones")
    void shouldListFilteredTimeZones() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/timezones")
                        .param("prefix", "Asia")
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Timezones retrieved successfully"))
                .andExpect(jsonPath("$.data.prefix").value("Asia"))
                .andExpect(jsonPath("$.data.limit").value(3))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.timeZones.length()").value(3))
                .andExpect(jsonPath("$.data.timeZones[*].timeZone", everyItem(startsWith("Asia/"))))
                .andExpect(jsonPath("$.data.timeZones[0].timeZone").value("Asia/Aden"))
                .andExpect(jsonPath("$.data.timeZones[0].offset").isString())
                .andExpect(jsonPath("$.data.timeZones[0].offsetSeconds").isNumber())
                .andExpect(jsonPath("$.data.timeZones[0].localDateTime").isString());
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/timezones - should apply the default limit of 50")
    void shouldListDefaultTimeZones() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/timezones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.prefix").value(""))
                .andExpect(jsonPath("$.data.limit").value(50))
                .andExpect(jsonPath("$.data.timeZones.length()").value(50));
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/timezones - should reject out-of-range limit with 400")
    void shouldRejectOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/timezones")
                        .param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Limit must be between 1 and 200")));

        mockMvc.perform(get("/api/v1/utils/datetime/timezones")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Limit must be between 1 and 200")));
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/timezones - should reject non-numeric limit with 400 not 500")
    void shouldRejectNonNumericLimit() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/timezones")
                        .param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid value 'abc' for parameter 'limit'")));
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

    @Test
    @DisplayName("POST /api/v1/utils/datetime/convert - should answer 400 (not 500) for forced seconds overflowing epoch millis")
    void shouldRejectForcedSecondsOutsideMillisRangeWith400() throws Exception {
        String body = """
                {
                    "input": "9223372036854776",
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
    @DisplayName("POST /api/v1/utils/datetime/shift - should return 400 BAD_REQUEST when the shift overflows epoch millis")
    void shouldReturnBadRequestForEpochMillisOverflowOnShift() throws Exception {
        // 292278000 YEARS stays inside the ZonedDateTime year range but overflows long millis.
        String body = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 292278000,
                    "unit": "YEARS",
                    "targetTimeZone": "UTC"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("epoch-millis range")));
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
