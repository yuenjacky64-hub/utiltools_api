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
}
