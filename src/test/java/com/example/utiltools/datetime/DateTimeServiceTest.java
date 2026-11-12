package com.example.utiltools.datetime;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysRequest;
import com.example.utiltools.datetime.dto.DateTimeCronRequest;
import com.example.utiltools.datetime.dto.DateTimeCronResponse;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeOverlapRequest;
import com.example.utiltools.datetime.dto.DateTimeOverlapResponse;
import com.example.utiltools.datetime.dto.DateTimeDurationRequest;
import com.example.utiltools.datetime.dto.DateTimeDurationResponse;
import com.example.utiltools.datetime.dto.DateTimeRangeRequest;
import com.example.utiltools.datetime.dto.DateTimeRangeResponse;
import com.example.utiltools.datetime.dto.DateTimeOverlapRequest;
import com.example.utiltools.datetime.dto.DateTimeOverlapResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import com.example.utiltools.datetime.dto.DateTimeWeekResponse;
import com.example.utiltools.datetime.dto.TimeZoneInfo;
import com.example.utiltools.datetime.dto.TimeZoneListResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
        DateTimeConvertRequest request = new DateTimeConvertRequest("1730335200000", "Asia/Tokyo", "yyyy-MM-dd HH:mm:ss", null);
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
        DateTimeConvertRequest request = new DateTimeConvertRequest("2026-01-01T15:00:00Z", "America/New_York", null, null);
        DateTimeConvertResponse response = dateTimeService.convert(request);

        assertThat(response.epochSeconds()).isEqualTo(1767279600L);
        assertThat(response.targetTimeZone()).isEqualTo("America/New_York");
        // EST is UTC-5
        assertThat(response.formattedTarget()).contains("2026-01-01T10:00:00-05:00");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when invalid timezone provided")
    void shouldThrowOnInvalidTimeZone() {
        DateTimeConvertRequest request = new DateTimeConvertRequest("1730335200000", "Invalid/Zone", null, null);
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
    @DisplayName("Should clamp month-end when adding one month (2026-01-31 + 1 MONTHS -> 2026-02-28)")
    void shouldClampMonthEndWhenAddingOneMonth() {
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", 1L, "MONTHS", null);
        DateTimeShiftResponse response = dateTimeService.shift(request);

        assertThat(response.formattedTarget()).isEqualTo("2026-02-28T12:00:00Z");
        assertThat(response.isoUtc()).isEqualTo("2026-02-28T12:00:00Z");
        assertThat(response.epochSeconds()).isEqualTo(1772280000L);
        assertThat(response.epochMillis()).isEqualTo(1772280000000L);
        assertThat(response.targetTimeZone()).isEqualTo("UTC");
        assertThat(response.appliedAmount()).isEqualTo(1L);
        assertThat(response.appliedUnit()).isEqualTo("MONTHS");
    }

    @Test
    @DisplayName("Should shift by weeks with a positive amount (2026-01-31 + 2 WEEKS -> 2026-02-14)")
    void shouldShiftByWeeksWithPositiveAmount() {
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", 2L, "weeks", null);
        DateTimeShiftResponse response = dateTimeService.shift(request);

        assertThat(response.formattedTarget()).isEqualTo("2026-02-14T12:00:00Z");
        assertThat(response.appliedUnit()).isEqualTo("WEEKS");
    }

    @Test
    @DisplayName("Should subtract when the amount is negative (2026-03-31 - 1 MONTHS -> 2026-02-28)")
    void shouldShiftNegativeAmount() {
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-03-31T12:00:00Z", "UTC", -1L, "MONTHS", null);
        DateTimeShiftResponse response = dateTimeService.shift(request);

        assertThat(response.formattedTarget()).isEqualTo("2026-02-28T12:00:00Z");
        assertThat(response.appliedAmount()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Should shift across the DST spring-forward gap in the target zone")
    void shouldShiftAcrossDstSpringForward() {
        // 2026-03-08T01:30:00-05:00 + 1 hour of elapsed time = 2026-03-08T03:30:00-04:00
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-03-08T01:30:00-05:00", "America/New_York", 1L, "HOURS", null);
        DateTimeShiftResponse response = dateTimeService.shift(request);

        assertThat(response.formattedTarget()).isEqualTo("2026-03-08T03:30:00-04:00");
        assertThat(response.epochSeconds()).isEqualTo(1772955000L);
        assertThat(response.targetTimeZone()).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("Should shift across the DST fall-back repeated hour in the target zone")
    void shouldShiftAcrossDstFallBack() {
        // 01:30 EDT (-04:00) + 1 hour of elapsed time lands on the repeated 01:30 EST (-05:00)
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-11-01T01:30:00-04:00", "America/New_York", 1L, "HOURS", null);
        DateTimeShiftResponse response = dateTimeService.shift(request);

        long inputEpochMillis = 1793511000000L; // 2026-11-01T05:30:00Z
        assertThat(response.formattedTarget()).isEqualTo("2026-11-01T01:30:00-05:00");
        assertThat(response.epochMillis()).isEqualTo(inputEpochMillis + 3_600_000L);
        assertThat(response.epochSeconds()).isEqualTo(1793514600L);
    }

    @Test
    @DisplayName("Should format the shift result with the supplied custom pattern")
    void shouldFormatShiftResultWithCustomPattern() {
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", 1L, "MONTHS", "yyyy-MM-dd HH:mm");
        DateTimeShiftResponse response = dateTimeService.shift(request);

        assertThat(response.formattedTarget()).isEqualTo("2026-02-28 12:00");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for an unknown timezone on shift")
    void shouldThrowOnUnknownTimeZoneForShift() {
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "Invalid/Zone", 1L, "MONTHS", null);
        assertThatThrownBy(() -> dateTimeService.shift(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown or invalid timezone");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for an invalid custom pattern on shift")
    void shouldThrowOnInvalidCustomPatternForShift() {
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", 1L, "MONTHS", "yyyy-MM-dd 'unterminated");
        assertThatThrownBy(() -> dateTimeService.shift(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid date-time pattern");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException instead of a DateTimeException for an out-of-range amount")
    void shouldThrowOnOutOfRangeAmount() {
        DateTimeShiftRequest request = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", Long.MAX_VALUE, "YEARS", null);
        assertThatThrownBy(() -> dateTimeService.shift(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Shift amount out of range");

        // Inside the ZonedDateTime year range (year 292 280 026) but outside the long millisecond
        // range, so the escape happens at the epoch-millis conversion, not inside the unit switch.
        DateTimeShiftRequest yearRangeRequest = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", 292_278_000L, "YEARS", null);
        assertThatThrownBy(() -> dateTimeService.shift(yearRangeRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the epoch-millis range");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for zero amount and unsupported unit on shift")
    void shouldThrowOnZeroAmountAndUnsupportedUnit() {
        DateTimeShiftRequest zeroAmount = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", 0L, "MONTHS", null);
        assertThatThrownBy(() -> dateTimeService.shift(zeroAmount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must not be zero");

        DateTimeShiftRequest badUnit = new DateTimeShiftRequest(
                "2026-01-31T12:00:00Z", "UTC", 1L, "FORTNIGHTS", null);
        assertThatThrownBy(() -> dateTimeService.shift(badUnit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported unit");
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

        assertThatThrownBy(() -> dateTimeService.listTimeZones(null, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Limit must be between 1 and 200, got: -5");

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

    @Test
    @DisplayName("Should honor explicit EPOCH_SECONDS for a 10-digit value")
    void shouldConvertExplicitEpochSeconds() {
        DateTimeConvertRequest request = new DateTimeConvertRequest("1730335200", "UTC", null, "EPOCH_SECONDS");
        DateTimeConvertResponse response = dateTimeService.convert(request);

        assertThat(response.epochMillis()).isEqualTo(1730335200000L);
        assertThat(response.epochSeconds()).isEqualTo(1730335200L);
        assertThat(response.isoUtc()).isEqualTo("2024-10-31T00:40:00Z");
    }

    @Test
    @DisplayName("Should honor explicit EPOCH_MILLIS for a 13-digit value")
    void shouldConvertExplicitEpochMillis() {
        DateTimeConvertRequest request = new DateTimeConvertRequest("1730335200000", "UTC", null, "EPOCH_MILLIS");
        DateTimeConvertResponse response = dateTimeService.convert(request);

        assertThat(response.epochMillis()).isEqualTo(1730335200000L);
        assertThat(response.epochSeconds()).isEqualTo(1730335200L);
        assertThat(response.isoUtc()).isEqualTo("2024-10-31T00:40:00Z");
    }

    @Test
    @DisplayName("Should force ISO-8601 parsing and reject numeric input under ISO_8601")
    void shouldForceIso8601AndRejectNumericInput() {
        DateTimeConvertRequest isoRequest =
                new DateTimeConvertRequest("2024-10-31T09:40:00+09:00", "UTC", null, "ISO_8601");
        DateTimeConvertResponse response = dateTimeService.convert(isoRequest);
        assertThat(response.epochMillis()).isEqualTo(1730335200000L);

        DateTimeConvertRequest numericRequest = new DateTimeConvertRequest("1730335200", "UTC", null, "ISO_8601");
        assertThatThrownBy(() -> dateTimeService.convert(numericRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match inputFormat ISO_8601");
    }

    @Test
    @DisplayName("Should accept lower-case format and treat blank inputFormat as auto-detect")
    void shouldAcceptLowerCaseFormatAndTreatBlankAsAutoDetect() {
        DateTimeConvertResponse lowerCase = dateTimeService.convert(
                new DateTimeConvertRequest("1000000000", "UTC", null, "epoch_millis"));
        assertThat(lowerCase.epochMillis()).isEqualTo(1000000000L);
        assertThat(lowerCase.epochSeconds()).isEqualTo(1000000L);

        DateTimeConvertResponse omitted = dateTimeService.convert(
                new DateTimeConvertRequest("1000000000", "UTC", null, null));
        DateTimeConvertResponse empty = dateTimeService.convert(
                new DateTimeConvertRequest("1000000000", "UTC", null, ""));
        DateTimeConvertResponse whitespace = dateTimeService.convert(
                new DateTimeConvertRequest("1000000000", "UTC", null, "   "));

        assertThat(omitted.epochMillis()).isEqualTo(1000000000000L);
        assertThat(empty.epochMillis()).isEqualTo(1000000000000L);
        assertThat(whitespace.epochMillis()).isEqualTo(1000000000000L);
    }

    @Test
    @DisplayName("Should raise IllegalArgumentException for out-of-range forced epoch seconds")
    void shouldReturnIllegalArgumentForOutOfRangeForcedEpoch() {
        DateTimeConvertRequest request =
                new DateTimeConvertRequest("9223372036854775807", "UTC", null, "EPOCH_SECONDS");

        assertThatThrownBy(() -> dateTimeService.convert(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match inputFormat EPOCH_SECONDS");
    }

    @Test
    @DisplayName("Should raise IllegalArgumentException for forced epoch seconds that overflow epoch millis")
    void shouldReturnIllegalArgumentForForcedSecondsOutsideMillisRange() {
        // 9223372036854776 s is a valid Instant (year 292278994) but toEpochMilli() would overflow
        DateTimeConvertRequest request =
                new DateTimeConvertRequest("9223372036854776", "UTC", null, "EPOCH_SECONDS");

        assertThatThrownBy(() -> dateTimeService.convert(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match inputFormat EPOCH_SECONDS")
                .hasMessageContaining("epoch milliseconds");
    }

    @Test
    @DisplayName("Should convert forced epoch seconds at the epoch-millis boundary")
    void shouldConvertForcedEpochSecondsAtMillisBoundary() {
        DateTimeConvertRequest upper =
                new DateTimeConvertRequest("9223372036854775", "UTC", null, "EPOCH_SECONDS");
        DateTimeConvertRequest lower =
                new DateTimeConvertRequest("-9223372036854775", "UTC", null, "EPOCH_SECONDS");

        assertThat(dateTimeService.convert(upper).epochMillis()).isEqualTo(9223372036854775000L);
        assertThat(dateTimeService.convert(lower).epochMillis()).isEqualTo(-9223372036854775000L);
    }

    @Test
    @DisplayName("Should reject non-numeric input for forced EPOCH_MILLIS")
    void shouldRejectNonNumericMillis() {
        DateTimeConvertRequest request = new DateTimeConvertRequest("12.5", "UTC", null, "EPOCH_MILLIS");

        assertThatThrownBy(() -> dateTimeService.convert(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match inputFormat EPOCH_MILLIS");
    }

    @Test
    @DisplayName("Should parse ISO-8601 duration components and total seconds")
    void shouldParseIsoDurationComponents() {
        DateTimeDurationResponse response =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "P1DT2H30M", null, null));

        assertThat(response.action()).isEqualTo("PARSE");
        assertThat(response.input()).isEqualTo("P1DT2H30M");
        assertThat(response.negative()).isFalse();
        assertThat(response.days()).isEqualTo(1);
        assertThat(response.hours()).isEqualTo(2);
        assertThat(response.minutes()).isEqualTo(30);
        assertThat(response.seconds()).isZero();
        assertThat(response.nanos()).isZero();
        assertThat(response.totalSeconds()).isEqualTo(95400L);
        assertThat(response.iso()).isEqualTo("P1DT2H30M");

        DateTimeDurationResponse zero =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "PT0S", null, null));
        assertThat(zero.totalSeconds()).isZero();
        assertThat(zero.iso()).isEqualTo("PT0S");

        DateTimeDurationResponse lowercaseAction =
                dateTimeService.duration(new DateTimeDurationRequest("parse", "+PT1H", null, null));
        assertThat(lowercaseAction.action()).isEqualTo("PARSE");
        assertThat(lowercaseAction.iso()).isEqualTo("PT1H");
    }

    @Test
    @DisplayName("Should normalise weeks to days and reject calendar-dependent units")
    void shouldNormalizeWeeksAndRejectCalendarUnits() {
        DateTimeDurationResponse response =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "P2W", null, null));

        assertThat(response.days()).isEqualTo(14);
        assertThat(response.totalSeconds()).isEqualTo(1209600L);
        assertThat(response.iso()).isEqualTo("P14D");

        assertThatThrownBy(() -> dateTimeService.duration(new DateTimeDurationRequest("PARSE", "P1M", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported calendar unit (years or months are not allowed)");
        assertThatThrownBy(() -> dateTimeService.duration(new DateTimeDurationRequest("PARSE", "P1Y", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported calendar unit (years or months are not allowed)");

        // The M after T is minutes, not a month.
        DateTimeDurationResponse minutes =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "PT1M", null, null));
        assertThat(minutes.minutes()).isEqualTo(1);
        assertThat(minutes.iso()).isEqualTo("PT1M");
    }

    @Test
    @DisplayName("Should parse negative and fractional-second durations with sign-magnitude components")
    void shouldParseNegativeAndFractionalDurations() {
        DateTimeDurationResponse negative =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "-PT1H30M", null, null));

        assertThat(negative.negative()).isTrue();
        assertThat(negative.hours()).isEqualTo(1);
        assertThat(negative.minutes()).isEqualTo(30);
        assertThat(negative.totalSeconds()).isEqualTo(-5400L);
        assertThat(negative.iso()).isEqualTo("-PT1H30M");

        DateTimeDurationResponse fractional =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "PT1.5S", null, null));

        assertThat(fractional.seconds()).isEqualTo(1);
        assertThat(fractional.nanos()).isEqualTo(500000000);
        assertThat(fractional.totalSeconds()).isEqualTo(1L);
        assertThat(fractional.iso()).isEqualTo("PT1.5S");

        // Sign-magnitude keeps -1.5s exact instead of Duration.getSeconds()'s floor of -2.
        DateTimeDurationResponse negativeFraction =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "-PT1.5S", null, null));

        assertThat(negativeFraction.negative()).isTrue();
        assertThat(negativeFraction.seconds()).isEqualTo(1);
        assertThat(negativeFraction.nanos()).isEqualTo(500000000);
        assertThat(negativeFraction.totalSeconds()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Should format signed seconds and round-trip a parsed fractional duration")
    void shouldFormatSecondsAndRoundTripFractionalDuration() {
        DateTimeDurationResponse formatted =
                dateTimeService.duration(new DateTimeDurationRequest("FORMAT", null, -5400L, null));

        assertThat(formatted.action()).isEqualTo("FORMAT");
        assertThat(formatted.input()).isNull();
        assertThat(formatted.negative()).isTrue();
        assertThat(formatted.days()).isZero();
        assertThat(formatted.hours()).isEqualTo(1);
        assertThat(formatted.minutes()).isEqualTo(30);
        assertThat(formatted.totalSeconds()).isEqualTo(-5400L);
        assertThat(formatted.iso()).isEqualTo("-PT1H30M");

        assertThat(dateTimeService.duration(new DateTimeDurationRequest("FORMAT", null, 0L, null)).iso())
                .isEqualTo("PT0S");
        assertThat(dateTimeService.duration(new DateTimeDurationRequest("FORMAT", null, 0L, 500000000)).iso())
                .isEqualTo("PT0.5S");

        DateTimeDurationResponse parsed =
                dateTimeService.duration(new DateTimeDurationRequest("PARSE", "PT1.5S", null, null));
        DateTimeDurationResponse roundTrip = dateTimeService.duration(
                new DateTimeDurationRequest("FORMAT", null, parsed.totalSeconds(), parsed.nanos()));

        assertThat(roundTrip.seconds()).isEqualTo(parsed.seconds());
        assertThat(roundTrip.nanos()).isEqualTo(parsed.nanos());
        assertThat(roundTrip.iso()).isEqualTo(parsed.iso());
    }

    @Test
    @DisplayName("Should reject malformed, overlong and out-of-range durations")
    void shouldRejectMalformedAndOutOfRangeDurations() {
        assertThatThrownBy(() -> dateTimeService.duration(new DateTimeDurationRequest("PARSE", "P", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to parse ISO-8601 duration");
        assertThatThrownBy(() -> dateTimeService.duration(new DateTimeDurationRequest("PARSE", "PT1H2X", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to parse ISO-8601 duration");
        assertThatThrownBy(() -> dateTimeService.duration(new DateTimeDurationRequest("PARSE", "  ", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration input cannot be empty");
        assertThatThrownBy(() -> dateTimeService.duration(new DateTimeDurationRequest("PARSE", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration input cannot be empty");
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("PARSE", "P" + "1".repeat(65) + "D", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration input is too long (max 64 characters)");
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("PARSE", "PT1.1234567891S", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fractional seconds are limited to nanosecond precision");

        // 20 digits fails Long.parseLong; 106751991167301 weeks overflows the long product.
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("PARSE", "P99999999999999999999W", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration out of range");
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("PARSE", "P106751991167301W", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration out of range");
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("FORMAT", null, Long.MIN_VALUE, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration out of range");
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("FORMAT", null, 1L, 1000000000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanos must be between 0 and 999999999");
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("FORMAT", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seconds is required when action is FORMAT");
        assertThatThrownBy(() -> dateTimeService.duration(
                new DateTimeDurationRequest("BOGUS", null, 1L, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported action");
    }

    @Test
    @DisplayName("Should add a business day by skipping the weekend")
    void shouldMoveForwardSkippingWeekend() {
        DateTimeBusinessDaysResponse response = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 1, null, null));

        assertThat(response.isoUtc()).isEqualTo("2026-01-05T17:00:00Z");
        assertThat(response.epochSeconds()).isEqualTo(1767632400L);
        assertThat(response.epochMillis()).isEqualTo(1767632400000L);
        assertThat(response.targetTimeZone()).isEqualTo("UTC");
        assertThat(response.formattedTarget()).isEqualTo("2026-01-05T17:00:00Z");
        assertThat(response.appliedAmount()).isEqualTo(1);
        assertThat(response.skippedWeekendDays()).isEqualTo(2);
        assertThat(response.skippedHolidayDays()).isZero();
    }

    @Test
    @DisplayName("Should skip holidays, count a weekend holiday only as a weekend skip and deduplicate")
    void shouldSkipHolidaysAndCountWeekendHolidayAsWeekend() {
        // 2026-01-02T17:00:00Z is a Friday; the next Monday 2026-01-05 is a holiday, so the result
        // moves to Tuesday 2026-01-06 while the weekend still accounts for two skips.
        DateTimeBusinessDaysResponse withMondayHoliday = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 1, null, List.of("2026-01-05")));

        assertThat(withMondayHoliday.isoUtc()).isEqualTo("2026-01-06T17:00:00Z");
        assertThat(withMondayHoliday.skippedWeekendDays()).isEqualTo(2);
        assertThat(withMondayHoliday.skippedHolidayDays()).isEqualTo(1);
        assertThat(withMondayHoliday.appliedAmount()).isEqualTo(1);

        // A holiday that falls on a Saturday (2026-01-03) must not increment the holiday counter.
        DateTimeBusinessDaysResponse withWeekendHoliday = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 1, null, List.of("2026-01-03")));

        assertThat(withWeekendHoliday.isoUtc()).isEqualTo("2026-01-05T17:00:00Z");
        assertThat(withWeekendHoliday.skippedWeekendDays()).isEqualTo(2);
        assertThat(withWeekendHoliday.skippedHolidayDays()).isZero();

        // Duplicate holiday entries collapse into one calendar date.
        DateTimeBusinessDaysResponse withDuplicateHoliday = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 1, null,
                        List.of("2026-01-05", "2026-01-05")));

        assertThat(withDuplicateHoliday.isoUtc()).isEqualTo("2026-01-06T17:00:00Z");
        assertThat(withDuplicateHoliday.skippedHolidayDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should move backward over a weekend and return the input unchanged for amount 0")
    void shouldMoveBackwardAndReturnInputForZeroAmount() {
        DateTimeBusinessDaysResponse backward = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-05T17:00:00Z", "UTC", -1, null, null));

        assertThat(backward.isoUtc()).isEqualTo("2026-01-02T17:00:00Z");
        assertThat(backward.appliedAmount()).isEqualTo(-1);
        assertThat(backward.skippedWeekendDays()).isEqualTo(2);
        assertThat(backward.skippedHolidayDays()).isZero();

        DateTimeBusinessDaysResponse zero = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-05T17:00:00Z", "UTC", 0, null, List.of("2026-01-05")));

        assertThat(zero.isoUtc()).isEqualTo("2026-01-05T17:00:00Z");
        assertThat(zero.epochSeconds()).isEqualTo(Instant.parse("2026-01-05T17:00:00Z").getEpochSecond());
        assertThat(zero.appliedAmount()).isZero();
        assertThat(zero.skippedWeekendDays()).isZero();
        assertThat(zero.skippedHolidayDays()).isZero();
    }

    @Test
    @DisplayName("Should preserve the local wall clock across the DST spring-forward transition")
    void shouldPreserveWallClockAcrossDstSpringForward() {
        // Friday 2026-03-06 09:00 EST (UTC-5); the next business day is Monday 2026-03-09, already EDT (UTC-4).
        DateTimeBusinessDaysResponse response = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-03-06T09:00:00-05:00", "America/New_York", 1, null, null));

        assertThat(response.isoUtc()).isEqualTo("2026-03-09T13:00:00Z");
        assertThat(response.epochMillis()).isEqualTo(1773061200000L);
        assertThat(response.formattedTarget()).isEqualTo("2026-03-09T09:00:00-04:00");
        assertThat(response.targetTimeZone()).isEqualTo("America/New_York");
        assertThat(response.skippedWeekendDays()).isEqualTo(2);

        DateTimeBusinessDaysResponse custom = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-03-06T09:00:00-05:00", "America/New_York", 1,
                        "yyyy-MM-dd HH:mm:ss", null));
        assertThat(custom.formattedTarget()).isEqualTo("2026-03-09 09:00:00");
    }

    @Test
    @DisplayName("Should accept the +-10000 bound without spinning beyond the scan cap")
    void shouldAcceptTheMaximumAmount() {
        DateTimeBusinessDaysResponse response = dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 10000, null, null));

        assertThat(response.appliedAmount()).isEqualTo(10000);
        assertThat(response.skippedWeekendDays()).isPositive();
        assertThat(Instant.parse(response.isoUtc())).isAfter(Instant.parse("2026-01-02T17:00:00Z"));
    }

    @Test
    @DisplayName("Should reject invalid business-day requests with IllegalArgumentException")
    void shouldRejectInvalidBusinessDayRequests() {
        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("  ", "UTC", 1, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input datetime or timestamp is required");

        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount is required");

        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 10001, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be between -10000 and 10000");

        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "Invalid/Zone", 1, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown or invalid timezone");

        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 1, null, List.of(" "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holiday entries must not be blank");

        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 1, null, List.of("05/01/2026"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid holiday date");

        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("not-a-date", "UTC", 1, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to parse input datetime");

        List<String> tooManyHolidays = new ArrayList<>();
        for (int i = 0; i < 367; i++) {
            tooManyHolidays.add("2026-01-05");
        }
        assertThatThrownBy(() -> dateTimeService.businessDays(
                new DateTimeBusinessDaysRequest("2026-01-02T17:00:00Z", "UTC", 1, null, tooManyHolidays)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holidays must contain at most 366 entries");
    }

    @Test
    @DisplayName("Should resolve ISO week for the Monday that rolls over into the next week-year")
    void shouldResolveIsoWeekForMondayWeekYearRollover() {
        DateTimeWeekResponse response = dateTimeService.week("2024-12-30");

        assertThat(response.date()).isEqualTo("2024-12-30");
        assertThat(response.isoWeekYear()).isEqualTo(2025);
        assertThat(response.isoWeek()).isEqualTo(1);
        assertThat(response.dayOfWeek()).isEqualTo("MONDAY");
        assertThat(response.dayOfYear()).isEqualTo(365);
        assertThat(response.weekend()).isFalse();
    }

    @Test
    @DisplayName("Should resolve week 53 of the previous week-year")
    void shouldResolveWeek53OfPreviousWeekYear() {
        DateTimeWeekResponse response = dateTimeService.week("2021-01-01");

        assertThat(response.date()).isEqualTo("2021-01-01");
        assertThat(response.isoWeekYear()).isEqualTo(2020);
        assertThat(response.isoWeek()).isEqualTo(53);
        assertThat(response.dayOfWeek()).isEqualTo("FRIDAY");
    }

    @Test
    @DisplayName("Should resolve the weekend flag and day of year, Sunday included")
    void shouldResolveWeekendAndDayOfYear() {
        DateTimeWeekResponse saturday = dateTimeService.week("2026-11-14");

        assertThat(saturday.isoWeekYear()).isEqualTo(2026);
        assertThat(saturday.isoWeek()).isEqualTo(46);
        assertThat(saturday.dayOfWeek()).isEqualTo("SATURDAY");
        assertThat(saturday.dayOfYear()).isEqualTo(318);
        assertThat(saturday.weekend()).isTrue();

        DateTimeWeekResponse sunday = dateTimeService.week("2026-11-15");
        assertThat(sunday.isoWeek()).isEqualTo(46);
        assertThat(sunday.weekend()).isTrue();

        DateTimeWeekResponse monday = dateTimeService.week("2026-11-16");
        assertThat(monday.isoWeek()).isEqualTo(47);
        assertThat(monday.weekend()).isFalse();

        DateTimeWeekResponse padded = dateTimeService.week(" 2026-11-14 ");
        assertThat(padded.date()).isEqualTo("2026-11-14");
        assertThat(padded.dayOfWeek()).isEqualTo("SATURDAY");
    }

    @Test
    @DisplayName("Should reject null, blank and malformed dates on week")
    void shouldRejectNullBlankAndMalformedDatesOnWeek() {
        assertThatThrownBy(() -> dateTimeService.week(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");

        assertThatThrownBy(() -> dateTimeService.week("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");

        assertThatThrownBy(() -> dateTimeService.week("2026-13-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");

        assertThatThrownBy(() -> dateTimeService.week("31/12/2024"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    @DisplayName("Should resolve 2025-W01-MONDAY to 2024-12-30 across the ISO week-year boundary")
    void shouldResolveIsoWeekCrossingWeekYearBoundary() {
        DateTimeWeekResponse response = dateTimeService.weekDate(2025, 1, "MONDAY");

        assertThat(response.date()).isEqualTo("2024-12-30");
        assertThat(response.isoWeekYear()).isEqualTo(2025);
        assertThat(response.isoWeek()).isEqualTo(1);
        assertThat(response.dayOfWeek()).isEqualTo("MONDAY");
        assertThat(response.weekend()).isFalse();
    }

    @Test
    @DisplayName("Should resolve 2020-W53-THURSDAY to 2020-12-31 (week 53 only exists in 53-week years)")
    void shouldResolveWeek53OfA53WeekYear() {
        DateTimeWeekResponse response = dateTimeService.weekDate(2020, 53, "THURSDAY");

        assertThat(response.date()).isEqualTo("2020-12-31");
        assertThat(response.isoWeekYear()).isEqualTo(2020);
        assertThat(response.isoWeek()).isEqualTo(53);
        assertThat(response.dayOfWeek()).isEqualTo("THURSDAY");
        assertThat(response.weekend()).isFalse();

        assertThatThrownBy(() -> dateTimeService.weekDate(2021, 53, "THURSDAY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist in week-year 2021");
    }

    @Test
    @DisplayName("Should accept case-insensitive day names and 1..7 numbers, defaulting to MONDAY")
    void shouldAcceptCaseInsensitiveDayNamesAndNumbers() {
        assertThat(dateTimeService.weekDate(2025, 1, "friday").date()).isEqualTo("2025-01-03");
        assertThat(dateTimeService.weekDate(2025, 1, "sunday").date()).isEqualTo("2025-01-05");
        assertThat(dateTimeService.weekDate(2025, 1, "5").date()).isEqualTo("2025-01-03");
        assertThat(dateTimeService.weekDate(2025, 1, "7").date()).isEqualTo("2025-01-05");
        assertThat(dateTimeService.weekDate(2025, 1, null).date()).isEqualTo("2024-12-30");
        assertThat(dateTimeService.weekDate(2025, 1, " ").date()).isEqualTo("2024-12-30");

        DateTimeWeekResponse sunday = dateTimeService.weekDate(2025, 1, "7");
        assertThat(sunday.dayOfWeek()).isEqualTo("SUNDAY");
        assertThat(sunday.weekend()).isTrue();
        assertThat(sunday.dayOfYear()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should reject out-of-range years, out-of-range weeks and unknown day tokens with 400")
    void shouldRejectOutOfRangeYearsWeeksAndUnknownDays() {
        assertThatThrownBy(() -> dateTimeService.weekDate(0, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("year must be between 1 and 9999");
        assertThatThrownBy(() -> dateTimeService.weekDate(10000, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("year must be between 1 and 9999");
        assertThatThrownBy(() -> dateTimeService.weekDate(2025, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("week must be between 1 and 53");
        assertThatThrownBy(() -> dateTimeService.weekDate(2025, 54, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("week must be between 1 and 53");
        assertThatThrownBy(() -> dateTimeService.weekDate(2025, 1, "FRIDAYDAY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day must be MONDAY..SUNDAY (case-insensitive) or 1..7");
        assertThatThrownBy(() -> dateTimeService.weekDate(2025, 1, "8"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day must be MONDAY..SUNDAY (case-insensitive) or 1..7");
        // 2021 has 52 ISO weeks, so 2021-W53 must be rejected.
        assertThatThrownBy(() -> dateTimeService.weekDate(2021, 53, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist in week-year 2021");
    }

    @Test
    @DisplayName("Should generate an inclusive daily range and echo the resolved request")
    void shouldGenerateInclusiveDailyRange() {
        DateTimeRangeResponse response = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-05", null, null, null));

        assertThat(response.startDate()).isEqualTo("2026-01-01");
        assertThat(response.endDate()).isEqualTo("2026-01-05");
        assertThat(response.unit()).isEqualTo("DAYS");
        assertThat(response.step()).isEqualTo(1);
        assertThat(response.inclusive()).isTrue();
        assertThat(response.count()).isEqualTo(5);
        assertThat(response.dates()).containsExactly(
                "2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05");

        assertThat(dateTimeService.range(new DateTimeRangeRequest("2026-01-01", "2026-01-05", "  ", 2, true))
                .dates()).containsExactly("2026-01-01", "2026-01-03", "2026-01-05");
    }

    @Test
    @DisplayName("Should apply a weekly step and resolve the unit case-insensitively")
    void shouldApplyWeeklyStep() {
        DateTimeRangeResponse biweekly = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-05", "2026-02-02", "weeks", 2, null));
        assertThat(biweekly.unit()).isEqualTo("WEEKS");
        assertThat(biweekly.step()).isEqualTo(2);
        assertThat(biweekly.dates()).containsExactly("2026-01-05", "2026-01-19", "2026-02-02");

        DateTimeRangeResponse weekly = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-05", "2026-01-26", "Weeks", null, null));
        assertThat(weekly.dates()).containsExactly(
                "2026-01-05", "2026-01-12", "2026-01-19", "2026-01-26");
    }

    @Test
    @DisplayName("Should clamp month-end points to the start date, not to the previous point")
    void shouldClampMonthEndRelativeToStartDate() {
        DateTimeRangeResponse response = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-31", "2026-05-31", "MONTHS", 1, true));

        assertThat(response.dates()).containsExactly(
                "2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31");
    }

    @Test
    @DisplayName("Should clamp leap-day yearly points to 28 February")
    void shouldGenerateLeapDayYearlyRange() {
        DateTimeRangeResponse response = dateTimeService.range(
                new DateTimeRangeRequest("2024-02-29", "2027-02-28", "years", 1, null));

        assertThat(response.unit()).isEqualTo("YEARS");
        assertThat(response.dates()).containsExactly(
                "2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28");
    }

    @Test
    @DisplayName("Should omit the end date when inclusive is false")
    void shouldOmitExclusiveEnd() {
        DateTimeRangeResponse response = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-05", "DAYS", 1, false));

        assertThat(response.inclusive()).isFalse();
        assertThat(response.dates()).containsExactly("2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04");
    }

    @Test
    @DisplayName("Should treat startDate == endDate as a single date when inclusive and empty when exclusive")
    void shouldHandleSingleDayRange() {
        DateTimeRangeResponse single = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-01", null, null, null));
        assertThat(single.count()).isEqualTo(1);
        assertThat(single.dates()).containsExactly("2026-01-01");

        DateTimeRangeResponse empty = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-01", null, null, false));
        assertThat(empty.count()).isZero();
        assertThat(empty.dates()).isEmpty();
    }

    @Test
    @DisplayName("Should allow exactly 1000 points and reject the 1001st")
    void shouldBoundTheRangeAtOneThousandPoints() {
        DateTimeRangeResponse response = dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2028-09-26", "DAYS", 1, true));
        assertThat(response.count()).isEqualTo(1000);
        assertThat(response.dates()).hasSize(1000);
        assertThat(response.dates().get(999)).isEqualTo("2028-09-26");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2028-09-27", "DAYS", 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1000 dates");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-02", "DAYS", 0, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step must be between 1 and 1000");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-02", "DAYS", 1001, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step must be between 1 and 1000");
    }

    @Test
    @DisplayName("Should reject a reversed range and an unsupported unit")
    void shouldRejectReversedRangeAndUnsupportedUnit() {
        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-05", "2026-01-01", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate must not be after endDate");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-05", "HOURS", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit must be one of DAYS, WEEKS, MONTHS, YEARS");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-05", "FORTNIGHTS", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit must be one of DAYS, WEEKS, MONTHS, YEARS");
    }

    @Test
    @DisplayName("Should reject a null request, an unparseable date and calendar overflow")
    void shouldRejectNullRequestUnparseableDateAndOverflow() {
        assertThatThrownBy(() -> dateTimeService.range(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request body is required");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest(null, "2026-01-05", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate must be a valid yyyy-MM-dd date");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-02-30", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endDate must be a valid yyyy-MM-dd date");

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("+999999999-12-31", "+999999999-12-31", "DAYS", 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the supported calendar range");
    }

    @Test
    @DisplayName("Should enumerate the 9999-12-31 calendar edge without overflowing")
    void shouldHandleMaximumYearBoundary() {
        // DOD-07: the last date the request pattern admits still yields its own single point, because the
        // next stepped point (10 999-12-31) is a valid LocalDate that is simply out of range.
        DateTimeRangeResponse upperEdge = dateTimeService.range(
                new DateTimeRangeRequest("9999-12-31", "9999-12-31", "YEARS", 1000, true));

        assertThat(upperEdge.count()).isEqualTo(1);
        assertThat(upperEdge.dates()).containsExactly("9999-12-31");

        DateTimeRangeResponse lastTwoDays = dateTimeService.range(
                new DateTimeRangeRequest("9999-12-30", "9999-12-31", "DAYS", 1, true));

        assertThat(lastTwoDays.dates()).containsExactly("9999-12-30", "9999-12-31");
    }

    @Test
    @DisplayName("Should truncate a hostile unit name in the rejection message")
    void shouldAbbreviateUnsupportedUnitEcho() {
        String hostileUnit = "Z".repeat(200);

        assertThatThrownBy(() -> dateTimeService.range(
                new DateTimeRangeRequest("2026-01-01", "2026-01-05", hostileUnit, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit must be one of DAYS, WEEKS, MONTHS, YEARS: " + "Z".repeat(64))
                .hasMessageNotContaining("Z".repeat(65));
    }

    @Test
    @DisplayName("Should compute the half-open intersection of partially overlapping intervals")
    void shouldComputePartialOverlap() {
        DateTimeOverlapResponse response = dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-05T00:00:00Z",
                        "2024-10-03T00:00:00Z", "2024-10-07T00:00:00Z"));

        assertThat(response.overlap()).isTrue();
        assertThat(response.overlapStart()).isEqualTo("2024-10-03T00:00:00Z");
        assertThat(response.overlapEnd()).isEqualTo("2024-10-05T00:00:00Z");
        assertThat(response.overlapMillis()).isEqualTo(2L * 24 * 60 * 60 * 1000);
    }

    @Test
    @DisplayName("Should return the contained interval as the intersection of containment cases")
    void shouldComputeContainmentOverlap() {
        DateTimeOverlapResponse response = dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-10T00:00:00Z",
                        "2024-10-03T12:00:00Z", "2024-10-05T06:00:00Z"));

        assertThat(response.overlap()).isTrue();
        assertThat(response.overlapStart()).isEqualTo("2024-10-03T12:00:00Z");
        assertThat(response.overlapEnd()).isEqualTo("2024-10-05T06:00:00Z");
        assertThat(response.overlapMillis())
                .isEqualTo(Duration.between(Instant.parse("2024-10-03T12:00:00Z"),
                        Instant.parse("2024-10-05T06:00:00Z")).toMillis());
    }

    @Test
    @DisplayName("Should return the full interval when both intervals are identical")
    void shouldComputeIdenticalIntervalOverlap() {
        DateTimeOverlapResponse response = dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-03T00:00:00Z",
                        "2024-10-01T00:00:00Z", "2024-10-03T00:00:00Z"));

        assertThat(response.overlap()).isTrue();
        assertThat(response.overlapStart()).isEqualTo("2024-10-01T00:00:00Z");
        assertThat(response.overlapEnd()).isEqualTo("2024-10-03T00:00:00Z");
        assertThat(response.overlapMillis()).isEqualTo(2L * 24 * 60 * 60 * 1000);
    }

    @Test
    @DisplayName("Should report disjoint intervals with null bounds and zero overlapMillis")
    void shouldReportDisjointIntervals() {
        DateTimeOverlapResponse response = dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-02T00:00:00Z",
                        "2024-10-03T00:00:00Z", "2024-10-04T00:00:00Z"));

        assertThat(response.overlap()).isFalse();
        assertThat(response.overlapStart()).isNull();
        assertThat(response.overlapEnd()).isNull();
        assertThat(response.overlapMillis()).isZero();
    }

    @Test
    @DisplayName("Should treat adjacent half-open boundaries (endA == startB) as disjoint")
    void shouldReportAdjacentIntervalsAsDisjoint() {
        DateTimeOverlapResponse response = dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-02T00:00:00Z",
                        "2024-10-02T00:00:00Z", "2024-10-03T00:00:00Z"));

        assertThat(response.overlap()).isFalse();
        assertThat(response.overlapStart()).isNull();
        assertThat(response.overlapEnd()).isNull();
        assertThat(response.overlapMillis()).isZero();
    }

    @Test
    @DisplayName("Should parse overlap bounds with the same auto-detect grammar as diff")
    void shouldAutoDetectEpochSecondsAndMillisForOverlapBounds() {
        DateTimeOverlapResponse response = dateTimeService.overlap(
                new DateTimeOverlapRequest("1727740800", "1727913600",
                        "1727827200000", "1727913600000"));

        // 1727740800s = 2024-10-01T00:00:00Z, 1727913600s = 2024-10-03T00:00:00Z,
        // 1727827200000ms = 2024-10-02T00:00:00Z, 1727913600000ms = 2024-10-03T00:00:00Z
        assertThat(response.overlap()).isTrue();
        assertThat(response.overlapStart()).isEqualTo("2024-10-02T00:00:00Z");
        assertThat(response.overlapEnd()).isEqualTo("2024-10-03T00:00:00Z");
        assertThat(response.overlapMillis()).isEqualTo(24L * 60 * 60 * 1000);
    }

    @Test
    @DisplayName("Should reject a reversed first interval (endA before startA) with IllegalArgumentException")
    void shouldRejectReversedFirstInterval() {
        assertThatThrownBy(() -> dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-05T00:00:00Z", "2024-10-01T00:00:00Z",
                        "2024-10-03T00:00:00Z", "2024-10-07T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endA must be strictly after startA");
    }

    @Test
    @DisplayName("Should reject a zero-length first interval (endA equal to startA) with IllegalArgumentException")
    void shouldRejectZeroLengthFirstInterval() {
        assertThatThrownBy(() -> dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-01T00:00:00Z",
                        "2024-10-03T00:00:00Z", "2024-10-07T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endA must be strictly after startA");
    }

    @Test
    @DisplayName("Should reject a reversed second interval (endB before startB) with IllegalArgumentException")
    void shouldRejectReversedSecondInterval() {
        assertThatThrownBy(() -> dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-02T00:00:00Z",
                        "2024-10-07T00:00:00Z", "2024-10-03T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endB must be strictly after startB");
    }

    @Test
    @DisplayName("Should reject a zero-length second interval (endB equal to startB) with IllegalArgumentException")
    void shouldRejectZeroLengthSecondInterval() {
        assertThatThrownBy(() -> dateTimeService.overlap(
                new DateTimeOverlapRequest("2024-10-01T00:00:00Z", "2024-10-02T00:00:00Z",
                        "2024-10-03T00:00:00Z", "2024-10-03T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endB must be strictly after startB");
    }

    @Test
    @DisplayName("Should expand a 6-field cron expression into an exact ordered UTC sequence")
    void shouldExpandCronSequenceInUtc() {
        DateTimeCronResponse response = dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 12 * * *", "2026-01-01T00:00:00Z", "UTC", 3));

        assertThat(response.expression()).isEqualTo("0 0 12 * * *");
        assertThat(response.from()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(response.timeZone()).isEqualTo("UTC");
        assertThat(response.count()).isEqualTo(3);
        assertThat(response.runs()).hasSize(3);
        assertThat(response.runs().get(0).utcIsoInstant()).isEqualTo("2026-01-01T12:00:00Z");
        assertThat(response.runs().get(1).utcIsoInstant()).isEqualTo("2026-01-02T12:00:00Z");
        assertThat(response.runs().get(2).utcIsoInstant()).isEqualTo("2026-01-03T12:00:00Z");
        assertThat(response.runs().get(0).localDateTime()).isEqualTo("2026-01-01T12:00:00Z");
        assertThat(response.runs().get(0).epochMillis())
                .isEqualTo(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli());
    }

    @Test
    @DisplayName("Should treat a 5-field cron expression as seconds-zero and default the zone to UTC")
    void shouldExpandFiveFieldCronWithDefaultZone() {
        DateTimeCronResponse response = dateTimeService.cronNext(
                new DateTimeCronRequest("0 12 * * *", "2026-01-01T00:00:00Z", null, 2));

        assertThat(response.expression()).isEqualTo("0 12 * * *");
        assertThat(response.timeZone()).isEqualTo("UTC");
        assertThat(response.runs()).hasSize(2);
        assertThat(response.runs().get(0).utcIsoInstant()).isEqualTo("2026-01-01T12:00:00Z");
        assertThat(response.runs().get(1).utcIsoInstant()).isEqualTo("2026-01-02T12:00:00Z");
    }

    @Test
    @DisplayName("Should expand Spring @daily and @hourly macros")
    void shouldExpandCronMacros() {
        DateTimeCronResponse daily = dateTimeService.cronNext(
                new DateTimeCronRequest("@daily", "2026-01-01T00:00:00Z", "UTC", 3));
        assertThat(daily.runs().get(0).utcIsoInstant()).isEqualTo("2026-01-02T00:00:00Z");
        assertThat(daily.runs().get(1).utcIsoInstant()).isEqualTo("2026-01-03T00:00:00Z");
        assertThat(daily.runs().get(2).utcIsoInstant()).isEqualTo("2026-01-04T00:00:00Z");

        DateTimeCronResponse hourly = dateTimeService.cronNext(
                new DateTimeCronRequest("@hourly", "2026-01-01T00:00:00Z", "UTC", 2));
        assertThat(hourly.runs().get(0).utcIsoInstant()).isEqualTo("2026-01-01T01:00:00Z");
        assertThat(hourly.runs().get(1).utcIsoInstant()).isEqualTo("2026-01-01T02:00:00Z");
    }

    @Test
    @DisplayName("Should keep the wall-clock hour in a fixed-offset zone (Asia/Tokyo)")
    void shouldExpandCronInTokyoZone() {
        DateTimeCronResponse response = dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 12 * * *", "2026-01-01T00:00:00Z", "Asia/Tokyo", 2));

        assertThat(response.timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(response.runs().get(0).utcIsoInstant()).isEqualTo("2026-01-01T03:00:00Z");
        assertThat(response.runs().get(0).localDateTime()).isEqualTo("2026-01-01T12:00:00+09:00");
        assertThat(response.runs().get(1).utcIsoInstant()).isEqualTo("2026-01-02T03:00:00Z");
    }

    @Test
    @DisplayName("Should keep the wall-clock hour across the DST spring-forward transition")
    void shouldExpandCronAcrossDstSpringForward() {
        // US DST 2026 starts on Sunday 2026-03-08: 09:00 NY is EST (-05:00) through 03-07
        // and EDT (-04:00) from 03-08, so the UTC instants drift one hour while the
        // wall clock stays at 09:00.
        DateTimeCronResponse response = dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 9 * * *", "2026-03-06T00:00:00Z", "America/New_York", 3));

        assertThat(response.runs().get(0).utcIsoInstant()).isEqualTo("2026-03-06T14:00:00Z");
        assertThat(response.runs().get(0).localDateTime()).isEqualTo("2026-03-06T09:00:00-05:00");
        assertThat(response.runs().get(1).utcIsoInstant()).isEqualTo("2026-03-07T14:00:00Z");
        assertThat(response.runs().get(1).localDateTime()).isEqualTo("2026-03-07T09:00:00-05:00");
        assertThat(response.runs().get(2).utcIsoInstant()).isEqualTo("2026-03-08T13:00:00Z");
        assertThat(response.runs().get(2).localDateTime()).isEqualTo("2026-03-08T09:00:00-04:00");
    }

    @Test
    @DisplayName("Should default the cron occurrence count to 5 when omitted")
    void shouldDefaultCronCountToFive() {
        DateTimeCronResponse response = dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 12 * * *", "2026-01-01T00:00:00Z", "UTC", null));

        assertThat(response.count()).isEqualTo(5);
        assertThat(response.runs()).hasSize(5);
        assertThat(response.runs().get(4).utcIsoInstant()).isEqualTo("2026-01-05T12:00:00Z");
    }

    @Test
    @DisplayName("Should reject a wrong field count, invalid expression, unknown zone, unparseable from and out-of-bounds count")
    void shouldRejectInvalidCronInputs() {
        assertThatThrownBy(() -> dateTimeService.cronNext(
                new DateTimeCronRequest("0 12 * *", "2026-01-01T00:00:00Z", "UTC", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have 5 or 6 fields");

        assertThatThrownBy(() -> dateTimeService.cronNext(
                new DateTimeCronRequest("61 0 12 * * *", "2026-01-01T00:00:00Z", "UTC", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");

        assertThatThrownBy(() -> dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 12 * * *", "2026-01-01T00:00:00Z", "Mars/Olympus", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown or invalid timezone");

        assertThatThrownBy(() -> dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 12 * * *", "not-a-time", "UTC", 3)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 12 * * *", "2026-01-01T00:00:00Z", "UTC", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count must be between 1 and 100");

        assertThatThrownBy(() -> dateTimeService.cronNext(
                new DateTimeCronRequest("0 0 12 * * *", "2026-01-01T00:00:00Z", "UTC", 101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count must be between 1 and 100");
    }
}
