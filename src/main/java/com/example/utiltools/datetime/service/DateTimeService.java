package com.example.utiltools.datetime.service;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
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

    public DateTimeConvertResponse convert(DateTimeConvertRequest request) {
        Instant instant = parseInstant(request.input());
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
}
