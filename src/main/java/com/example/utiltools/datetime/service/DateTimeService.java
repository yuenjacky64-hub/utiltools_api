package com.example.utiltools.datetime.service;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class DateTimeService {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+$");

    public DateTimeConvertResponse convert(DateTimeConvertRequest request) {
        Instant instant = parseInstant(request.input());
        ZoneId zoneId = parseZoneId(request.targetTimeZone());

        ZonedDateTime zonedDateTime = instant.atZone(zoneId);

        String formatted = format(zonedDateTime, request.customPattern());

        return new DateTimeConvertResponse(
                instant.toEpochMilli(),
                instant.getEpochSecond(),
                DateTimeFormatter.ISO_INSTANT.format(instant),
                zoneId.getId(),
                formatted
        );
    }

    /**
     * Applies a signed amount in the given unit to the input datetime inside the target
     * timezone, using {@link ZonedDateTime} calendar arithmetic so that month-end clamping
     * and DST transitions are resolved by {@code java.time}.
     *
     * <p>Throws {@link IllegalArgumentException} when the input cannot be parsed, the
     * timezone is unknown, the unit is not one of the supported constants, the amount is
     * missing or zero, the custom pattern is invalid, or the arithmetic result is out of
     * range.
     */
    public DateTimeShiftResponse shift(DateTimeShiftRequest request) {
        Instant instant = parseInstant(request.input());
        ZoneId zoneId = parseZoneId(request.targetTimeZone());

        Long amount = request.amount();
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (amount == 0L) {
            throw new IllegalArgumentException("amount must not be zero");
        }

        String unit = request.unit() == null ? "" : request.unit().trim().toUpperCase(Locale.ROOT);
        ZonedDateTime zonedDateTime = instant.atZone(zoneId);

        ZonedDateTime shifted;
        try {
            shifted = switch (unit) {
                case "YEARS" -> zonedDateTime.plusYears(amount);
                case "MONTHS" -> zonedDateTime.plusMonths(amount);
                case "WEEKS" -> zonedDateTime.plusWeeks(amount);
                case "DAYS" -> zonedDateTime.plusDays(amount);
                case "HOURS" -> zonedDateTime.plusHours(amount);
                case "MINUTES" -> zonedDateTime.plusMinutes(amount);
                case "SECONDS" -> zonedDateTime.plusSeconds(amount);
                default -> throw new IllegalArgumentException("Unsupported unit: " + request.unit()
                        + ". Expected one of YEARS, MONTHS, WEEKS, DAYS, HOURS, MINUTES, SECONDS");
            };
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException("Shift amount out of range: " + amount + " " + unit, e);
        }

        Instant shiftedInstant = shifted.toInstant();

        return new DateTimeShiftResponse(
                shiftedInstant.toEpochMilli(),
                shiftedInstant.getEpochSecond(),
                DateTimeFormatter.ISO_INSTANT.format(shiftedInstant),
                zoneId.getId(),
                format(shifted, request.customPattern()),
                amount,
                unit
        );
    }

    public DateTimeDiffResponse diff(DateTimeDiffRequest request) {
        Instant start = parseInstant(request.start());
        Instant end = parseInstant(request.end());

        Duration duration = Duration.between(start, end).abs();
        long days = duration.toDays();
        long hours = duration.toHours();
        long minutes = duration.toMinutes();
        long seconds = duration.toSeconds();
        long millis = duration.toMillis();

        String humanReadable = String.format("%d days %d hours %d minutes %d seconds",
                days, duration.toHoursPart(), duration.toMinutesPart(), duration.toSecondsPart());

        return new DateTimeDiffResponse(days, hours, minutes, seconds, millis, humanReadable);
    }

    public CurrentTimeResponse getCurrentTime(String timeZoneStr) {
        ZoneId zoneId = parseZoneId(timeZoneStr);
        Instant now = Instant.now();
        ZonedDateTime localZdt = now.atZone(zoneId);

        return new CurrentTimeResponse(
                now.toEpochMilli(),
                now.getEpochSecond(),
                DateTimeFormatter.ISO_INSTANT.format(now),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(localZdt),
                zoneId.getId()
        );
    }

    private Instant parseInstant(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Datetime input cannot be empty");
        }
        String trimmed = input.trim();

        if (NUMERIC_PATTERN.matcher(trimmed).matches()) {
            long numericValue = Long.parseLong(trimmed);
            // If absolute value has more than 11 digits, assume milliseconds; otherwise seconds
            if (Math.abs(numericValue) > 99_999_999_999L) {
                return Instant.ofEpochMilli(numericValue);
            } else {
                return Instant.ofEpochSecond(numericValue);
            }
        }

        try {
            return Instant.parse(trimmed);
        } catch (DateTimeException ignored) {
        }

        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeException ignored) {
        }

        try {
            return ZonedDateTime.parse(trimmed).toInstant();
        } catch (DateTimeException ignored) {
        }

        throw new IllegalArgumentException("Unable to parse datetime string: " + input + ". Expected epoch millis/seconds or ISO-8601 string.");
    }

    private ZoneId parseZoneId(String timeZoneStr) {
        if (timeZoneStr == null || timeZoneStr.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timeZoneStr.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown or invalid timezone: " + timeZoneStr, e);
        }
    }

    private String format(ZonedDateTime zonedDateTime, String customPattern) {
        if (customPattern != null && !customPattern.isBlank()) {
            try {
                return DateTimeFormatter.ofPattern(customPattern).format(zonedDateTime);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid date-time pattern: " + customPattern, e);
            }
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zonedDateTime);
    }
}
