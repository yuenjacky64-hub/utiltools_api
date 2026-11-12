package com.example.utiltools.datetime;

import com.example.utiltools.audit.AuditTestSupport;
import com.example.utiltools.audit.controller.AuditController;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.service.TextService;
import com.example.utiltools.resilience.ResilienceConfig;
import com.example.utiltools.datetime.controller.DateTimeController;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({DateTimeController.class, AuditController.class})
@Import({AuditTestSupport.class, AuditJournalService.class, AuditReplayService.class, AuditedOperations.class,
        CryptoService.class, DateTimeService.class,
        JsonService.class, TextService.class, ResilienceConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {"utiltools.audit.max-entries=200", "utiltools.audit.max-replay-bytes=65536"})
class DateTimeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditJournalService auditJournal;

    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("POST /api/v1/utils/datetime/shift - should journal a SUCCEEDED DATETIME_SHIFT entry")
    void shouldJournalSucceededShiftEntry() throws Exception {
        String body = """
                {
                    "input": "2026-01-31T12:00:00Z",
                    "amount": 1,
                    "unit": "MONTHS",
                    "targetTimeZone": "UTC",
                    "customPattern": "yyyy-MM-dd HH:mm:ss"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        AuditEntry entry = auditJournal.list(1, null).get(0);
        assertThat(entry.operation()).isEqualTo(OperationType.DATETIME_SHIFT);
        assertThat(entry.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(entry.errorCode()).isNull();
        assertThat(entry.replayable()).isTrue();
        assertThat(entry.request().get("input").asText()).isEqualTo("2026-01-31T12:00:00Z");
        assertThat(entry.request().get("targetTimeZone").asText()).isEqualTo("UTC");
        assertThat(entry.request().get("amount").asLong()).isEqualTo(1L);
        assertThat(entry.request().get("unit").asText()).isEqualTo("MONTHS");
        assertThat(entry.request().get("customPattern").asText()).isEqualTo("yyyy-MM-dd HH:mm:ss");
        assertThat(entry.request().has("amountNonZero")).isFalse();
        assertThat(entry.result().get("isoUtc").asText()).isEqualTo("2026-02-28T12:00:00Z");
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

    @Test
    @DisplayName("POST /api/v1/utils/datetime/duration - should parse components and total seconds")
    void shouldParseDurationEndpointReturnsComponents() throws Exception {
        String body = """
                {
                    "action": "PARSE",
                    "input": "P1DT2H30M"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/duration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("PARSE"))
                .andExpect(jsonPath("$.data.input").value("P1DT2H30M"))
                .andExpect(jsonPath("$.data.iso").value("P1DT2H30M"))
                .andExpect(jsonPath("$.data.negative").value(false))
                .andExpect(jsonPath("$.data.days").value(1))
                .andExpect(jsonPath("$.data.hours").value(2))
                .andExpect(jsonPath("$.data.minutes").value(30))
                .andExpect(jsonPath("$.data.seconds").value(0))
                .andExpect(jsonPath("$.data.nanos").value(0))
                .andExpect(jsonPath("$.data.totalSeconds").value(95400));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/duration - should format negative seconds as canonical ISO text")
    void shouldFormatNegativeDurationEndpointReturnsIsoText() throws Exception {
        String body = """
                {
                    "action": "FORMAT",
                    "seconds": -5400
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/duration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("FORMAT"))
                .andExpect(jsonPath("$.data.iso").value("-PT1H30M"))
                .andExpect(jsonPath("$.data.negative").value(true))
                .andExpect(jsonPath("$.data.hours").value(1))
                .andExpect(jsonPath("$.data.minutes").value(30))
                .andExpect(jsonPath("$.data.totalSeconds").value(-5400));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/duration - should return 400 BAD_REQUEST for malformed durations")
    void shouldRejectMalformedDurationRequestsWith400() throws Exception {
        String unsupportedCalendarUnit = "unsupported calendar unit (years or months are not allowed)";
        String unparseable = "Unable to parse ISO-8601 duration";
        String empty = "Duration input cannot be empty";

        String[] bodies = {
                """
                {"action": "PARSE", "input": "P"}
                """,
                """
                {"action": "PARSE", "input": "P1M"}
                """,
                """
                {"action": "PARSE", "input": "PT1H2X"}
                """,
                """
                {"action": "PARSE", "input": ""}
                """
        };
        String[] messages = {unparseable, unsupportedCalendarUnit, unparseable, empty};

        for (int i = 0; i < bodies.length; i++) {
            mockMvc.perform(post("/api/v1/utils/datetime/duration")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodies[i]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messages[i])));
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/duration - is journalled as DATETIME_DURATION and replays identically")
    void shouldJournalAndReplayDuration() throws Exception {
        String body = """
                {
                    "action": "PARSE",
                    "input": "PT1.5S"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/duration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.iso").value("PT1.5S"));

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("DATETIME_DURATION"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.iso").value("PT1.5S"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        JsonNode storedResult = entry.get("result");
        AuditEntry before = auditJournal.find(id).orElseThrow();
        Instant completedAt = before.completedAt();

        MvcResult replay = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("DATETIME_DURATION"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"))
                .andReturn();

        JsonNode replayedResult = objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("data").get("result");
        assertThat(replayedResult).isEqualTo(storedResult);

        AuditEntry after = auditJournal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.DATETIME_DURATION);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isEqualTo(before.result());
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/duration - echoes X-Request-Id and rejects invalid fields with 400")
    void shouldPropagateRequestIdOnDuration() throws Exception {
        mockMvc.perform(post("/api/v1/utils/datetime/duration")
                        .header("X-Request-Id", "corr-duration01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "PARSE", "input": "P2W"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "corr-duration01"))
                .andExpect(jsonPath("$.data.iso").value("P14D"));

        mockMvc.perform(post("/api/v1/utils/datetime/duration")
                        .header("X-Request-Id", "corr-duration02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "BOGUS", "input": "PT1H"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "corr-duration02"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem("action must be one of PARSE, FORMAT")));

        mockMvc.perform(post("/api/v1/utils/datetime/duration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "FORMAT", "seconds": 1, "nanos": 1000000000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem("nanos must be between 0 and 999999999")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/business-days - skips the weekend and the holiday list")
    void shouldCalculateBusinessDaysSkippingWeekendAndHolidays() throws Exception {
        String weekend = """
                {
                    "input": "2026-01-02T17:00:00Z",
                    "amount": 1
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekend))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Business days calculated successfully"))
                .andExpect(jsonPath("$.data.isoUtc").value("2026-01-05T17:00:00Z"))
                .andExpect(jsonPath("$.data.epochMillis").value(1767632400000L))
                .andExpect(jsonPath("$.data.epochSeconds").value(1767632400L))
                .andExpect(jsonPath("$.data.targetTimeZone").value("UTC"))
                .andExpect(jsonPath("$.data.formattedTarget").value("2026-01-05T17:00:00Z"))
                .andExpect(jsonPath("$.data.appliedAmount").value(1))
                .andExpect(jsonPath("$.data.skippedWeekendDays").value(2))
                .andExpect(jsonPath("$.data.skippedHolidayDays").value(0));

        String withHolidays = """
                {
                    "input": "2026-01-02T17:00:00Z",
                    "targetTimeZone": "UTC",
                    "amount": 1,
                    "customPattern": "yyyy-MM-dd",
                    "holidays": ["2026-01-05", "2026-01-03", "2026-01-05"]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withHolidays))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isoUtc").value("2026-01-06T17:00:00Z"))
                .andExpect(jsonPath("$.data.formattedTarget").value("2026-01-06"))
                .andExpect(jsonPath("$.data.skippedWeekendDays").value(2))
                .andExpect(jsonPath("$.data.skippedHolidayDays").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/business-days - returns 400 for invalid requests")
    void shouldRejectInvalidBusinessDaysRequests() throws Exception {
        String blankInput = """
                {
                    "input": "   ",
                    "amount": 1
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankInput))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("input datetime or timestamp is required"));

        String missingAmount = """
                {
                    "input": "2026-01-02T17:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingAmount))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("amount is required"));

        String amountTooLarge = """
                {
                    "input": "2026-01-02T17:00:00Z",
                    "amount": 10001
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountTooLarge))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("amount must be between -10000 and 10000"));

        String amountTooSmall = """
                {
                    "input": "2026-01-02T17:00:00Z",
                    "amount": -10001
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountTooSmall))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("amount must be between -10000 and 10000"));

        String unknownTimeZone = """
                {
                    "input": "2026-01-02T17:00:00Z",
                    "targetTimeZone": "Invalid/Zone",
                    "amount": 1
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownTimeZone))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unknown or invalid timezone")));

        String malformedHoliday = """
                {
                    "input": "2026-01-02T17:00:00Z",
                    "amount": 1,
                    "holidays": ["05/01/2026"]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedHoliday))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid holiday date")));

        StringBuilder holidays = new StringBuilder();
        for (int i = 0; i < 367; i++) {
            if (i > 0) {
                holidays.append(',');
            }
            holidays.append("\"2026-01-05\"");
        }
        String tooManyHolidays = """
                {
                    "input": "2026-01-02T17:00:00Z",
                    "amount": 1,
                    "holidays": [%s]
                }
                """.formatted(holidays);

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooManyHolidays))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("holidays must contain at most 366 entries"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/business-days - is journalled as DATETIME_BUSINESS_DAYS and replays identically")
    void shouldJournalAndReplayBusinessDays() throws Exception {
        String body = """
                {
                    "input": "2026-03-06T09:00:00-05:00",
                    "targetTimeZone": "America/New_York",
                    "amount": 1,
                    "holidays": ["2026-03-09"]
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/business-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isoUtc").value("2026-03-10T13:00:00Z"))
                .andExpect(jsonPath("$.data.skippedHolidayDays").value(1));

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("DATETIME_BUSINESS_DAYS"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.isoUtc").value("2026-03-10T13:00:00Z"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        JsonNode storedResult = entry.get("result");
        AuditEntry before = auditJournal.find(id).orElseThrow();
        Instant completedAt = before.completedAt();
        assertThat(OperationType.DATETIME_BUSINESS_DAYS.isDeterministic()).isTrue();
        assertThat(before.replayable()).isTrue();

        MvcResult replay = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("DATETIME_BUSINESS_DAYS"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"))
                .andReturn();

        JsonNode replayedResult = objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("data").get("result");
        assertThat(replayedResult).isEqualTo(storedResult);

        AuditEntry after = auditJournal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.DATETIME_BUSINESS_DAYS);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isEqualTo(before.result());
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/week - should return the ISO week of 2024-12-30")
    void shouldReturnIsoWeek() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/week")
                        .param("date", "2024-12-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ISO week retrieved successfully"))
                .andExpect(jsonPath("$.data.date").value("2024-12-30"))
                .andExpect(jsonPath("$.data.isoWeekYear").value(2025))
                .andExpect(jsonPath("$.data.isoWeek").value(1))
                .andExpect(jsonPath("$.data.dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.data.dayOfYear").value(365))
                .andExpect(jsonPath("$.data.weekend").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/week - should resolve week-year rollover and weekend flags")
    void shouldReturnWeekYearRolloverAndWeekendFlags() throws Exception {
        mockMvc.perform(get("/api/v1/utils/datetime/week")
                        .param("date", "2021-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isoWeekYear").value(2020))
                .andExpect(jsonPath("$.data.isoWeek").value(53));

        mockMvc.perform(get("/api/v1/utils/datetime/week")
                        .param("date", "2026-11-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isoWeekYear").value(2026))
                .andExpect(jsonPath("$.data.isoWeek").value(46))
                .andExpect(jsonPath("$.data.dayOfWeek").value("SATURDAY"))
                .andExpect(jsonPath("$.data.dayOfYear").value(318))
                .andExpect(jsonPath("$.data.weekend").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/week - should reject missing and invalid dates with 400 and no journal entry")
    void shouldRejectMissingAndInvalidWeekDateWith400AndNoJournalEntry() throws Exception {
        int before = auditJournal.size();

        mockMvc.perform(get("/api/v1/utils/datetime/week"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("yyyy-MM-dd")));

        mockMvc.perform(get("/api/v1/utils/datetime/week")
                        .param("date", "2026-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("yyyy-MM-dd")));

        assertThat(auditJournal.size()).isEqualTo(before);
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/range - should generate an ordered range and echo the resolved request")
    void shouldReturnDateRange() throws Exception {
        String body = """
                {
                    "startDate": "2026-01-31",
                    "endDate": "2026-04-30",
                    "unit": "months"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Date range generated successfully"))
                .andExpect(jsonPath("$.data.startDate").value("2026-01-31"))
                .andExpect(jsonPath("$.data.endDate").value("2026-04-30"))
                .andExpect(jsonPath("$.data.unit").value("MONTHS"))
                .andExpect(jsonPath("$.data.step").value(1))
                .andExpect(jsonPath("$.data.inclusive").value(true))
                .andExpect(jsonPath("$.data.count").value(4))
                .andExpect(jsonPath("$.data.dates.length()").value(4))
                .andExpect(jsonPath("$.data.dates[0]").value("2026-01-31"))
                .andExpect(jsonPath("$.data.dates[1]").value("2026-02-28"))
                .andExpect(jsonPath("$.data.dates[2]").value("2026-03-31"))
                .andExpect(jsonPath("$.data.dates[3]").value("2026-04-30"));

        String steppedBody = """
                {
                    "startDate": "2026-01-01",
                    "endDate": "2026-01-05",
                    "step": 2,
                    "inclusive": false
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(steppedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").value("DAYS"))
                .andExpect(jsonPath("$.data.step").value(2))
                .andExpect(jsonPath("$.data.inclusive").value(false))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.dates[0]").value("2026-01-01"))
                .andExpect(jsonPath("$.data.dates[1]").value("2026-01-03"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/range - should reject a reversed range and an out-of-bounds step")
    void shouldReturnBadRequestForReversedRangeAndStepOutOfBounds() throws Exception {
        String reversed = """
                {
                    "startDate": "2026-01-05",
                    "endDate": "2026-01-01"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("startDate must not be after endDate")));

        String zeroStep = """
                {
                    "startDate": "2026-01-01",
                    "endDate": "2026-01-05",
                    "step": 0
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroStep))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("step must be between 1 and 1000")));

        String hugeStep = """
                {
                    "startDate": "2026-01-01",
                    "endDate": "2026-01-05",
                    "step": 1001
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hugeStep))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("step must be between 1 and 1000")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/range - should return BAD_REQUEST for an unsupported unit")
    void shouldReturnBadRequestForUnsupportedRangeUnit() throws Exception {
        String unknownUnit = """
                {
                    "startDate": "2026-01-01",
                    "endDate": "2026-01-05",
                    "unit": "HOURS"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownUnit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("unit must be one of DAYS, WEEKS, MONTHS, YEARS")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/range - should return VALIDATION_ERROR for blank or malformed dates")
    void shouldRejectBlankAndMalformedRangeDates() throws Exception {
        String blankStart = """
                {
                    "startDate": "",
                    "endDate": "2026-01-05"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankStart))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem("startDate is required")));

        String malformedStart = """
                {
                    "startDate": "31/12/2026",
                    "endDate": "2026-01-05"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedStart))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem("startDate must be yyyy-MM-dd")));

        String blankEnd = """
                {
                    "startDate": "2026-01-01",
                    "endDate": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankEnd))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem("endDate is required")));

        String malformedEnd = """
                {
                    "startDate": "2026-01-01",
                    "endDate": "2026-1-5"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedEnd))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem("endDate must be yyyy-MM-dd")));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/range - is journalled as DATETIME_RANGE and replays identically")
    void shouldJournalAndReplayDateTimeRange() throws Exception {
        String body = """
                {
                    "startDate": "2026-01-31",
                    "endDate": "2026-04-30",
                    "unit": "MONTHS",
                    "step": 1
                }
                """;

        int before = auditJournal.size();

        mockMvc.perform(post("/api/v1/utils/datetime/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(4));

        assertThat(auditJournal.size()).isEqualTo(before + 1);

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("DATETIME_RANGE"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.count").value(4))
                .andExpect(jsonPath("$.data[0].result.dates[1]").value("2026-02-28"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        JsonNode storedResult = entry.get("result");
        AuditEntry journalEntry = auditJournal.find(id).orElseThrow();
        Instant completedAt = journalEntry.completedAt();
        assertThat(OperationType.DATETIME_RANGE.isDeterministic()).isTrue();
        assertThat(journalEntry.replayable()).isTrue();
        assertThat(journalEntry.state()).isEqualTo(JournalState.SUCCEEDED);

        MvcResult replay = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("DATETIME_RANGE"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"))
                .andReturn();

        JsonNode replayedResult = objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("data").get("result");
        assertThat(replayedResult).isEqualTo(storedResult);

        assertThat(auditJournal.size()).isEqualTo(before + 1);
        AuditEntry after = auditJournal.find(id).orElseThrow();
        assertThat(after.operation()).isEqualTo(OperationType.DATETIME_RANGE);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.result()).isEqualTo(journalEntry.result());
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/week-date - resolves an ISO week to a calendar date without journaling")
    void shouldResolveIsoWeekDate() throws Exception {
        int before = auditJournal.size();

        mockMvc.perform(get("/api/v1/utils/datetime/week-date")
                        .param("year", "2025")
                        .param("week", "1")
                        .param("day", "MONDAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.date").value("2024-12-30"))
                .andExpect(jsonPath("$.data.isoWeekYear").value(2025))
                .andExpect(jsonPath("$.data.isoWeek").value(1))
                .andExpect(jsonPath("$.data.dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.data.weekend").value(false));

        mockMvc.perform(get("/api/v1/utils/datetime/week-date")
                        .param("year", "2020")
                        .param("week", "53")
                        .param("day", "THURSDAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2020-12-31"))
                .andExpect(jsonPath("$.data.isoWeek").value(53));

        // Case-insensitive day name; omitted day defaults to MONDAY.
        mockMvc.perform(get("/api/v1/utils/datetime/week-date")
                        .param("year", "2025")
                        .param("week", "1")
                        .param("day", "friday"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2025-01-03"))
                .andExpect(jsonPath("$.data.dayOfWeek").value("FRIDAY"));

        mockMvc.perform(get("/api/v1/utils/datetime/week-date")
                        .param("year", "2025")
                        .param("week", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2024-12-30"))
                .andExpect(jsonPath("$.data.dayOfWeek").value("MONDAY"));

        // Read-only route: the journal is untouched by successful or rejected calls alike.
        assertThat(auditJournal.size()).isEqualTo(before);
    }

    @Test
    @DisplayName("GET /api/v1/utils/datetime/week-date - rejects invalid year/week/day with 400 without journaling")
    void shouldRejectInvalidWeekDate() throws Exception {
        int before = auditJournal.size();

        mockMvc.perform(get("/api/v1/utils/datetime/week-date")
                        .param("year", "2021")
                        .param("week", "53"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("does not exist in week-year 2021")));

        mockMvc.perform(get("/api/v1/utils/datetime/week-date")
                        .param("year", "2025")
                        .param("week", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(get("/api/v1/utils/datetime/week-date")
                        .param("year", "2025")
                        .param("week", "1")
                        .param("day", "FRIDAYDAY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        assertThat(auditJournal.size()).isEqualTo(before);
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/overlap - reports the half-open intersection in the ApiResponse envelope")
    void shouldCalculateDateTimeOverlap() throws Exception {
        String body = """
                {
                    "startA": "2024-10-01T00:00:00Z",
                    "endA": "2024-10-05T00:00:00Z",
                    "startB": "2024-10-03T00:00:00Z",
                    "endB": "2024-10-07T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/overlap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("DateTime overlap calculated successfully"))
                .andExpect(jsonPath("$.data.overlap").value(true))
                .andExpect(jsonPath("$.data.overlapStart").value("2024-10-03T00:00:00Z"))
                .andExpect(jsonPath("$.data.overlapEnd").value("2024-10-05T00:00:00Z"))
                .andExpect(jsonPath("$.data.overlapMillis").value(2L * 24 * 60 * 60 * 1000));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/overlap - reports null bounds and zero millis for disjoint or adjacent intervals")
    void shouldReportDisjointDateTimeOverlap() throws Exception {
        String disjoint = """
                {
                    "startA": "2024-10-01T00:00:00Z",
                    "endA": "2024-10-02T00:00:00Z",
                    "startB": "2024-10-03T00:00:00Z",
                    "endB": "2024-10-04T00:00:00Z"
                }
                """;
        String adjacent = """
                {
                    "startA": "2024-10-01T00:00:00Z",
                    "endA": "2024-10-02T00:00:00Z",
                    "startB": "2024-10-02T00:00:00Z",
                    "endB": "2024-10-03T00:00:00Z"
                }
                """;

        for (String payload : new String[] {disjoint, adjacent}) {
            mockMvc.perform(post("/api/v1/utils/datetime/overlap")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.overlap").value(false))
                    .andExpect(jsonPath("$.data.overlapStart", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.overlapEnd", org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.overlapMillis").value(0L));
        }
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/overlap - rejects reversed or zero-length intervals with 400 BAD_REQUEST")
    void shouldRejectInvalidOverlapIntervals() throws Exception {
        int before = auditJournal.size();
        String reversed = """
                {
                    "startA": "2024-10-05T00:00:00Z",
                    "endA": "2024-10-01T00:00:00Z",
                    "startB": "2024-10-03T00:00:00Z",
                    "endB": "2024-10-07T00:00:00Z"
                }
                """;
        String zeroLength = """
                {
                    "startA": "2024-10-01T00:00:00Z",
                    "endA": "2024-10-01T00:00:00Z",
                    "startB": "2024-10-01T00:00:00Z",
                    "endB": "2024-10-02T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/overlap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("endA must be strictly after startA")));

        mockMvc.perform(post("/api/v1/utils/datetime/overlap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroLength))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("endA must be strictly after startA")));

        // The PENDING entry was already begun before the call, so each 400 journals a FAILED entry.
        assertThat(auditJournal.size()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/overlap - is journalled as DATETIME_OVERLAP and replays identically")
    void shouldJournalAndReplayDateTimeOverlap() throws Exception {
        String body = """
                {
                    "startA": "2024-10-01T00:00:00Z",
                    "endA": "2024-10-05T00:00:00Z",
                    "startB": "2024-10-03T00:00:00Z",
                    "endB": "2024-10-07T00:00:00Z"
                }
                """;

        int before = auditJournal.size();

        mockMvc.perform(post("/api/v1/utils/datetime/overlap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overlapStart").value("2024-10-03T00:00:00Z"));

        assertThat(auditJournal.size()).isEqualTo(before + 1);

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("DATETIME_OVERLAP"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.overlapStart").value("2024-10-03T00:00:00Z"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        JsonNode storedResult = entry.get("result");
        AuditEntry journalEntry = auditJournal.find(id).orElseThrow();
        assertThat(OperationType.DATETIME_OVERLAP.isDeterministic()).isTrue();
        assertThat(journalEntry.state()).isEqualTo(JournalState.SUCCEEDED);

        MvcResult replay = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("DATETIME_OVERLAP"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"))
                .andReturn();

        JsonNode replayedResult = objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("data").get("result");
        assertThat(replayedResult).isEqualTo(storedResult);

        // Replay must not append a second journal entry.
        assertThat(auditJournal.size()).isEqualTo(before + 1);
        AuditEntry after = auditJournal.find(id).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.result()).isEqualTo(journalEntry.result());
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/cron/next - returns an exact ordered UTC sequence")
    void shouldReturnCronNextSequence() throws Exception {
        String body = """
                {
                    "expression": "0 0 12 * * *",
                    "from": "2026-01-01T00:00:00Z",
                    "timeZone": "UTC",
                    "count": 3
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/cron/next")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.expression").value("0 0 12 * * *"))
                .andExpect(jsonPath("$.data.timeZone").value("UTC"))
                .andExpect(jsonPath("$.data.count").value(3))
                .andExpect(jsonPath("$.data.runs.length()").value(3))
                .andExpect(jsonPath("$.data.runs[0].utcIsoInstant").value("2026-01-01T12:00:00Z"))
                .andExpect(jsonPath("$.data.runs[1].utcIsoInstant").value("2026-01-02T12:00:00Z"))
                .andExpect(jsonPath("$.data.runs[2].utcIsoInstant").value("2026-01-03T12:00:00Z"))
                .andExpect(jsonPath("$.data.runs[0].localDateTime").value("2026-01-01T12:00:00Z"))
                .andExpect(jsonPath("$.data.runs[0].epochMillis")
                        .value(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/cron/next - accepts 5-field expressions and @ macros")
    void shouldAcceptFiveFieldAndMacroCronExpressions() throws Exception {
        String fiveField = """
                {
                    "expression": "0 12 * * *",
                    "from": "2026-01-01T00:00:00Z"
                }
                """;

        MvcResult first = mockMvc.perform(post("/api/v1/utils/datetime/cron/next")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fiveField))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeZone").value("UTC"))
                .andExpect(jsonPath("$.data.count").value(5))
                .andExpect(jsonPath("$.data.runs[0].utcIsoInstant").value("2026-01-01T12:00:00Z"))
                .andReturn();
        JsonNode firstRuns = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("data").get("runs");
        for (int i = 1; i < firstRuns.size(); i++) {
            assertThat(firstRuns.get(i).get("epochMillis").asLong())
                    .isGreaterThan(firstRuns.get(i - 1).get("epochMillis").asLong());
        }

        String macro = """
                {
                    "expression": "@daily",
                    "from": "2026-01-01T00:00:00Z",
                    "count": 2
                }
                """;
        mockMvc.perform(post("/api/v1/utils/datetime/cron/next")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(macro))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].utcIsoInstant").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.data.runs[1].utcIsoInstant").value("2026-01-03T00:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/cron/next - keeps the wall clock across a DST transition")
    void shouldKeepCronWallClockAcrossDstTransition() throws Exception {
        String body = """
                {
                    "expression": "0 0 9 * * *",
                    "from": "2026-03-06T00:00:00Z",
                    "timeZone": "America/New_York",
                    "count": 3
                }
                """;

        mockMvc.perform(post("/api/v1/utils/datetime/cron/next")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].localDateTime").value("2026-03-06T09:00:00-05:00"))
                .andExpect(jsonPath("$.data.runs[1].localDateTime").value("2026-03-07T09:00:00-05:00"))
                .andExpect(jsonPath("$.data.runs[2].localDateTime").value("2026-03-08T09:00:00-04:00"))
                .andExpect(jsonPath("$.data.runs[0].utcIsoInstant").value("2026-03-06T14:00:00Z"))
                .andExpect(jsonPath("$.data.runs[2].utcIsoInstant").value("2026-03-08T13:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/cron/next - rejects invalid expression, zone, from and count with 400")
    void cronNextInvalidInputsReturn400() throws Exception {
        int before = auditJournal.size();
        String[] serviceLevelBodies = {
                """
                { "expression": "61 0 12 * * *", "from": "2026-01-01T00:00:00Z", "timeZone": "UTC", "count": 3 }
                """,
                """
                { "expression": "0 0 12 *", "from": "2026-01-01T00:00:00Z", "timeZone": "UTC", "count": 3 }
                """,
                """
                { "expression": "0 0 12 * * *", "from": "2026-01-01T00:00:00Z", "timeZone": "Mars/Olympus", "count": 3 }
                """,
                """
                { "expression": "0 0 12 * * *", "from": "not-a-time", "timeZone": "UTC", "count": 3 }
                """,
        };
        for (String content : serviceLevelBodies) {
            mockMvc.perform(post("/api/v1/utils/datetime/cron/next")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }

        // Bean-validation rejections stop before the audit journal and surface as
        // VALIDATION_ERROR; the four service-level failures each journal a FAILED entry.
        String[] validationBodies = {
                """
                { "expression": "0 0 12 * * *", "from": "2026-01-01T00:00:00Z", "timeZone": "UTC", "count": 101 }
                """,
                """
                { "expression": "0 0 12 * * *", "from": "2026-01-01T00:00:00Z", "timeZone": "UTC", "count": 0 }
                """,
                """
                { "expression": "", "from": "2026-01-01T00:00:00Z" }
                """,
        };

        for (String content : validationBodies) {
            mockMvc.perform(post("/api/v1/utils/datetime/cron/next")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }

        assertThat(auditJournal.size()).isEqualTo(before + 4);
    }

    @Test
    @DisplayName("POST /api/v1/utils/datetime/cron/next - is journalled as DATETIME_CRON_NEXT and replays as VERIFIED")
    void cronNextJournalledAndReplaysVerified() throws Exception {
        String body = """
                {
                    "expression": "0 0 12 * * *",
                    "from": "2026-01-01T00:00:00Z",
                    "timeZone": "UTC",
                    "count": 2
                }
                """;

        int before = auditJournal.size();

        mockMvc.perform(post("/api/v1/utils/datetime/cron/next")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].utcIsoInstant").value("2026-01-01T12:00:00Z"));

        assertThat(auditJournal.size()).isEqualTo(before + 1);

        MvcResult entries = mockMvc.perform(get("/api/v1/utils/audit/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operation").value("DATETIME_CRON_NEXT"))
                .andExpect(jsonPath("$.data[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].result.runs[0].utcIsoInstant").value("2026-01-01T12:00:00Z"))
                .andReturn();

        JsonNode entry = objectMapper.readTree(entries.getResponse().getContentAsString()).get("data").get(0);
        String id = entry.get("id").asText();
        JsonNode storedResult = entry.get("result");
        AuditEntry journalEntry = auditJournal.find(id).orElseThrow();
        assertThat(OperationType.DATETIME_CRON_NEXT.isDeterministic()).isTrue();
        assertThat(journalEntry.state()).isEqualTo(JournalState.SUCCEEDED);

        MvcResult replay = mockMvc.perform(post("/api/v1/utils/audit/entries/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("DATETIME_CRON_NEXT"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.replay.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.replay.detail").value("results match"))
                .andReturn();

        JsonNode replayedResult = objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("data").get("result");
        assertThat(replayedResult).isEqualTo(storedResult);

        // Replay must not append a second journal entry.
        assertThat(auditJournal.size()).isEqualTo(before + 1);
        AuditEntry after = auditJournal.find(id).orElseThrow();
        assertThat(after.state()).isEqualTo(JournalState.SUCCEEDED);
        assertThat(after.result()).isEqualTo(journalEntry.result());
    }
}
