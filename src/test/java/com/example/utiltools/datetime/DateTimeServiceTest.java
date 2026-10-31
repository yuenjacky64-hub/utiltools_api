package com.example.utiltools.datetime;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.TimeZoneInfo;
import com.example.utiltools.datetime.dto.TimeZoneListResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

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

    @Test
    @DisplayName("Should return timezones in lexicographic order")
    void shouldReturnLexicographicallySortedZones() {
        TimeZoneListResponse response = dateTimeService.listTimeZones(null, 200);

        List<String> ids = response.timeZones().stream().map(TimeZoneInfo::timeZone).toList();
        assertThat(ids).isNotEmpty();
        assertThat(ids).isEqualTo(ids.stream().sorted().toList());
        assertThat(response.total()).isGreaterThanOrEqualTo(response.timeZones().size());
        assertThat(response.timeZones()).hasSize(Math.min(200, response.total()));
    }

    @Test
    @DisplayName("Should filter timezones case-insensitively by prefix")
    void shouldFilterCaseInsensitivelyByPrefix() {
        TimeZoneListResponse lower = dateTimeService.listTimeZones("asia", 50);
        TimeZoneListResponse upper = dateTimeService.listTimeZones(" Asia ", 50);

        List<String> lowerIds = lower.timeZones().stream().map(TimeZoneInfo::timeZone).toList();
        List<String> upperIds = upper.timeZones().stream().map(TimeZoneInfo::timeZone).toList();
        assertThat(lowerIds).isNotEmpty().isEqualTo(upperIds);
        assertThat(lowerIds).allMatch(id -> id.startsWith("Asia/"));
        assertThat(lower.prefix()).isEqualTo("asia");
        assertThat(upper.prefix()).isEqualTo("Asia");

        TimeZoneListResponse substring = dateTimeService.listTimeZones("Tokyo", 50);
        assertThat(substring.timeZones()).isEmpty();
        assertThat(substring.total()).isZero();

        TimeZoneListResponse nested = dateTimeService.listTimeZones("America/New", 50);
        assertThat(nested.timeZones()).isNotEmpty()
                .allMatch(info -> info.timeZone().startsWith("America/New"));
    }

    @Test
    @DisplayName("Should truncate to limit and report the total pre-truncation count")
    void shouldTruncateToLimitAndReportTotal() {
        long expectedTotal = ZoneId.getAvailableZoneIds().stream()
                .filter(id -> id.startsWith("Asia"))
                .count();

        TimeZoneListResponse response = dateTimeService.listTimeZones("Asia", 3);

        assertThat(response.timeZones()).hasSize(3);
        assertThat(response.limit()).isEqualTo(3);
        assertThat(response.total()).isEqualTo((int) expectedTotal);
        assertThat(response.total()).isGreaterThan(3);
    }

    @Test
    @DisplayName("Should report Asia/Tokyo offset as +09:00 / 32400 seconds")
    void shouldReportTokyoOffset() {
        TimeZoneListResponse response = dateTimeService.listTimeZones("Asia/Tokyo", 10);

        assertThat(response.timeZones()).hasSize(1);
        TimeZoneInfo tokyo = response.timeZones().get(0);
        assertThat(tokyo.timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(tokyo.offset()).isEqualTo("+09:00");
        assertThat(tokyo.offsetSeconds()).isEqualTo(32400);
        assertThat(tokyo.offset()).matches("[+-]\\d{2}:\\d{2}");
        assertThat(tokyo.localDateTime()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("Should reject limits outside 1-200 and accept the boundaries")
    void shouldRejectLimitOutsideRange() {
        assertThatThrownBy(() -> dateTimeService.listTimeZones(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Limit must be between 1 and 200, got: 0");

        assertThatThrownBy(() -> dateTimeService.listTimeZones(null, 201))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Limit must be between 1 and 200, got: 201");

        assertThat(dateTimeService.listTimeZones(null, 1).timeZones()).hasSize(1);
        assertThat(dateTimeService.listTimeZones(null, 200).timeZones()).hasSize(200);
    }

    @Test
    @DisplayName("Should compute every offset from a single captured instant")
    void shouldComputeOffsetsFromSingleInstant() {
        TimeZoneListResponse response = dateTimeService.listTimeZones(null, 200);

        List<Long> instants = response.timeZones().stream()
                .map(info -> OffsetDateTime.parse(info.localDateTime()).toEpochSecond())
                .distinct()
                .toList();
        assertThat(instants).hasSize(1);

        assertThat(response.timeZones()).allSatisfy(info -> assertThat(info.offsetSeconds())
                .isEqualTo(OffsetDateTime.parse(info.localDateTime()).getOffset().getTotalSeconds()));

        TimeZoneListResponse utcResponse = dateTimeService.listTimeZones("UTC", 10);
        TimeZoneInfo utc = utcResponse.timeZones().stream()
                .filter(info -> info.timeZone().equals("UTC"))
                .findFirst()
                .orElseThrow();
        assertThat(utc.offset()).isEqualTo("+00:00");
        assertThat(utc.offsetSeconds()).isZero();
    }
}
