package com.example.utiltools.datetime.service;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
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

    private static final String FORMAT_EPOCH_SECONDS = "EPOCH_SECONDS";
    private static final String FORMAT_EPOCH_MILLIS = "EPOCH_MILLIS";
    private static final String FORMAT_ISO_8601 = "ISO_8601";

    public DateTimeConvertResponse convert(DateTimeConvertRequest request) {
        Instant instant = parseInstant(request.input(), request.inputFormat());
        ZoneId zoneId = parseZoneId(request.targetTimeZone());

        ZonedDateTime zonedDateTime = instant.atZone(zoneId);

        String formatted;
        if (request.customPattern() != null && !request.customPattern().isBlank()) {
            try {
                formatted = DateTimeFormatter.ofPattern(request.customPattern()).format(zonedDateTime);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid date-time pattern: " + request.customPattern(), e);
            }
        } else {
            formatted = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zonedDateTime);
        }

        return new DateTimeConvertResponse(
                instant.toEpochMilli(),
                instant.getEpochSecond(),
                DateTimeFormatter.ISO_INSTANT.format(instant),
                zoneId.getId(),
                formatted
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
        try {
            return seconds ? Instant.ofEpochSecond(numericValue) : Instant.ofEpochMilli(numericValue);
        } catch (DateTimeException e) {
            throw mismatch(originalInput, format, expected, e);
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
}
