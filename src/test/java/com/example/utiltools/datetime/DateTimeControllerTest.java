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

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
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
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid value for parameter 'limit'")));
    }
}
