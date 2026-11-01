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
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;

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
}
