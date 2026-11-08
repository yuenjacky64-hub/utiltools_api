package com.example.utiltools.datetime;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeDurationRequest;
import com.example.utiltools.datetime.dto.DateTimeDurationResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
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
}
