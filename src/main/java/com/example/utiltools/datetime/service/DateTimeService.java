package com.example.utiltools.datetime.service;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import com.example.utiltools.datetime.dto.TimeZoneInfo;
import com.example.utiltools.datetime.dto.TimeZoneListResponse;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DateTimeService {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+$");
    private static final DateTimeFormatter OFFSET_FORMATTER = DateTimeFormatter.ofPattern("xxx");
    private static final int MAX_TIMEZONE_LIMIT = 200;

    private static final String FORMAT_EPOCH_SECONDS = "EPOCH_SECONDS";
    private static final String FORMAT_EPOCH_MILLIS = "EPOCH_MILLIS";
    private static final String FORMAT_ISO_8601 = "ISO_8601";

    /** Largest absolute epoch-seconds value whose millisecond form still fits in a {@code long}. */
    private static final long MAX_EPOCH_SECONDS_FOR_MILLIS = 9_223_372_036_854_775L;

    public DateTimeConvertResponse convert(DateTimeConvertRequest request) {
        Instant instant = parseInstant(request.input(), request.inputFormat());
        ZoneId zoneId = parseZoneId(request.targetTimeZone());

        ZonedDateTime zonedDateTime = instant.atZone(zoneId);

        String formatted = format(zonedDateTime, request.customPattern());

        return new DateTimeConvertResponse(
                toEpochMillis(instant, request.input(), request.inputFormat()),
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

    public TimeZoneListResponse listTimeZones(String prefix, int limit) {
        if (limit < 1 || limit > MAX_TIMEZONE_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and 200, got: " + limit);
        }

        String applied = (prefix == null) ? "" : prefix.trim();
        String needle = applied.toLowerCase(Locale.ROOT);

        Set<String> availableIds = ZoneId.getAvailableZoneIds();
        List<String> matched = new ArrayList<>();
        for (String id : availableIds) {
            if (id.toLowerCase(Locale.ROOT).startsWith(needle)) {
                matched.add(id);
            }
        }
        matched.sort(String::compareTo);

        int total = matched.size();
        List<String> selected = matched.subList(0, Math.min(limit, total));

        Instant now = Instant.now();
        List<TimeZoneInfo> entries = new ArrayList<>(selected.size());
        for (String id : selected) {
            ZonedDateTime zonedDateTime = now.atZone(ZoneId.of(id));
            entries.add(new TimeZoneInfo(
                    id,
                    OFFSET_FORMATTER.format(zonedDateTime),
                    zonedDateTime.getOffset().getTotalSeconds(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zonedDateTime.withNano(0))
            ));
        }

        return new TimeZoneListResponse(applied, limit, total, entries);
    }

    private Instant parseInstant(String input) {
        return parseInstant(input, null);
    }

    private Instant parseInstant(String input, String inputFormat) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Datetime input cannot be empty");
        }
        String trimmed = input.trim();
        String format = normalizeFormat(inputFormat);

        if (format == null) {
            return parseAutoDetected(trimmed, input);
        }

        switch (format) {
            case FORMAT_EPOCH_SECONDS -> {
                return fromEpoch(trimmed, input, format, true);
            }
            case FORMAT_EPOCH_MILLIS -> {
                return fromEpoch(trimmed, input, format, false);
            }
            case FORMAT_ISO_8601 -> {
                return parseIso8601Strict(trimmed, input, format);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported inputFormat: " + inputFormat
                            + ". Allowed values: EPOCH_SECONDS, EPOCH_MILLIS, ISO_8601");
        }
    }

    private Instant parseAutoDetected(String trimmed, String originalInput) {
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

        throw new IllegalArgumentException("Unable to parse datetime string: " + originalInput
                + ". Expected epoch millis/seconds or ISO-8601 string.");
    }

    private Instant fromEpoch(String trimmed, String originalInput, String format, boolean seconds) {
        String expected = seconds ? "epoch seconds (integer)" : "epoch milliseconds (integer)";
        if (!NUMERIC_PATTERN.matcher(trimmed).matches()) {
            throw mismatch(originalInput, format, expected);
        }
        long numericValue;
        try {
            numericValue = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw mismatch(originalInput, format, expected, e);
        }
        if (seconds && (numericValue > MAX_EPOCH_SECONDS_FOR_MILLIS || numericValue < -MAX_EPOCH_SECONDS_FOR_MILLIS)) {
            throw mismatch(originalInput, format, expected + " representable as epoch milliseconds");
        }
        try {
            return seconds ? Instant.ofEpochSecond(numericValue) : Instant.ofEpochMilli(numericValue);
        } catch (DateTimeException e) {
            throw mismatch(originalInput, format, expected, e);
        }
    }

    /**
     * Converts to epoch millis, mapping the {@link ArithmeticException} thrown by
     * {@link Instant#toEpochMilli()} for instants outside the {@code long} millisecond range onto the
     * documented 400 {@code BAD_REQUEST} path instead of letting it escape as a 500.
     */
    private long toEpochMillis(Instant instant, String originalInput, String inputFormat) {
        try {
            return instant.toEpochMilli();
        } catch (ArithmeticException e) {
            String format = normalizeFormat(inputFormat);
            String expected = "an instant representable in epoch milliseconds";
            if (format != null) {
                throw mismatch(originalInput, format, expected, e);
            }
            throw new IllegalArgumentException("Datetime " + originalInput + " is outside the epoch-millis range: expected "
                    + expected, e);
        }
    }

    private Instant parseIso8601Strict(String trimmed, String originalInput, String format) {
        String expected = "ISO-8601 datetime string";
        if (NUMERIC_PATTERN.matcher(trimmed).matches()) {
            throw mismatch(originalInput, format, expected);
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

        throw mismatch(originalInput, format, expected);
    }

    private IllegalArgumentException mismatch(String originalInput, String format, String expected) {
        return new IllegalArgumentException(
                "Input " + originalInput + " does not match inputFormat " + format + ": expected " + expected);
    }

    private IllegalArgumentException mismatch(String originalInput, String format, String expected, Throwable cause) {
        return new IllegalArgumentException(
                "Input " + originalInput + " does not match inputFormat " + format + ": expected " + expected, cause);
    }

    private String normalizeFormat(String inputFormat) {
        if (inputFormat == null || inputFormat.isBlank()) {
            return null;
        }
        return inputFormat.trim().toUpperCase(Locale.ROOT);
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
