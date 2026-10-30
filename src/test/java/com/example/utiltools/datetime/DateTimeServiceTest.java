package com.example.utiltools.datetime;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateTimeServiceTest {

    private DateTimeService dateTimeService;

    @BeforeEach
    void setUp() {
        dateTimeService = new DateTimeService();
    }

    @Test
    @DisplayName("Should convert epoch milliseconds to target timezone (Asia/Tokyo)")
    void shouldConvertEpochMillisToTargetTimeZone() {
        // 1730335200000 = 2024-10-31T00:40:00Z -> Tokyo (+09:00): 2024-10-31 09:40:00
        DateTimeConvertRequest request = new DateTimeConvertRequest("1730335200000", "Asia/Tokyo", "yyyy-MM-dd HH:mm:ss");
        DateTimeConvertResponse response = dateTimeService.convert(request);

        assertThat(response.epochMillis()).isEqualTo(1730335200000L);
        assertThat(response.epochSeconds()).isEqualTo(1730335200L);
        assertThat(response.isoUtc()).isEqualTo("2024-10-31T00:40:00Z");
        assertThat(response.targetTimeZone()).isEqualTo("Asia/Tokyo");
        assertThat(response.formattedTarget()).isEqualTo("2024-10-31 09:40:00");
    }

    @Test
    @DisplayName("Should convert ISO-8601 string to target timezone (America/New_York)")
    void shouldConvertIsoStringToTargetTimeZone() {
        DateTimeConvertRequest request = new DateTimeConvertRequest("2026-01-01T15:00:00Z", "America/New_York", null);
        DateTimeConvertResponse response = dateTimeService.convert(request);

        assertThat(response.epochSeconds()).isEqualTo(1767279600L);
        assertThat(response.targetTimeZone()).isEqualTo("America/New_York");
        // EST is UTC-5
        assertThat(response.formattedTarget()).contains("2026-01-01T10:00:00-05:00");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when invalid timezone provided")
    void shouldThrowOnInvalidTimeZone() {
        DateTimeConvertRequest request = new DateTimeConvertRequest("1730335200000", "Invalid/Zone", null);
        assertThatThrownBy(() -> dateTimeService.convert(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown or invalid timezone");
    }

    @Test
    @DisplayName("Should calculate difference between two timestamps accurately")
    void shouldCalculateDifferenceAccurately() {
        DateTimeDiffRequest request = new DateTimeDiffRequest("2026-01-01T00:00:00Z", "2026-01-03T12:30:00Z");
        DateTimeDiffResponse response = dateTimeService.diff(request);

        assertThat(response.days()).isEqualTo(2);
        assertThat(response.hours()).isEqualTo(60);
        assertThat(response.minutes()).isEqualTo(3630);
        assertThat(response.seconds()).isEqualTo(217800);
        assertThat(response.humanReadable()).isEqualTo("2 days 12 hours 30 minutes 0 seconds");
    }

    @Test
    @DisplayName("Should return current time for valid timezone")
    void shouldReturnCurrentTime() {
        CurrentTimeResponse response = dateTimeService.getCurrentTime("Asia/Taipei");

        assertThat(response.timeZone()).isEqualTo("Asia/Taipei");
        assertThat(response.epochMillis()).isGreaterThan(0);
        assertThat(response.utc()).isNotBlank();
        assertThat(response.local()).isNotBlank();
    }
}
