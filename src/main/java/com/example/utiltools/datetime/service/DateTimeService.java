package com.example.utiltools.datetime.service;

import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysRequest;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeDurationRequest;
import com.example.utiltools.datetime.dto.DateTimeDurationResponse;
import com.example.utiltools.datetime.dto.DateTimeRangeRequest;
import com.example.utiltools.datetime.dto.DateTimeRangeResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import com.example.utiltools.datetime.dto.DateTimeWeekResponse;
import com.example.utiltools.datetime.dto.TimeZoneInfo;
import com.example.utiltools.datetime.dto.TimeZoneListResponse;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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

    private static final int MAX_DURATION_INPUT_LENGTH = 64;
    private static final long SECONDS_PER_WEEK = 604_800L;
    private static final long SECONDS_PER_DAY = 86_400L;

    /** Week/day/hour/minute/second ISO-8601 duration grammar; anchored and linear-time. */
    private static final Pattern ISO_DURATION = Pattern.compile(
            "^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:\\.(\\d+))?S)?)?$");

    /** Bound on the signed business-day amount accepted by {@code businessDays}. */
    private static final int MAX_BUSINESS_DAY_AMOUNT = 10_000;

    /** Bound on caller-supplied holidays accepted by {@code businessDays}. */
    private static final int MAX_HOLIDAY_ENTRIES = 366;

    /** Hard cap on calendar days visited by the {@code businessDays} stepping loop. */
    private static final int MAX_SCAN_DAYS = 40_000;

    /** Bound on the untrusted {@code date} value echoed in a {@code week} error message. */
    private static final int MAX_WEEK_DATE_ECHO = 64;

    /** Expected input form named in every {@code week} validation message. */
    private static final String EXPECTED_WEEK_DATE = "ISO-8601 local date (yyyy-MM-dd)";

    /** Hard bound on generated dates: the 1001st in-range point is a 400. */
    public static final int MAX_RANGE_POINTS = 1000;

    /** Calendar-dependent year/month components in the date part (the {@code M} of {@code PT1M} is minutes). */
    private static final Pattern CALENDAR_UNIT = Pattern.compile("^P[^T]*\\d+(?:\\.\\d+)?[YM]");

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
                toEpochMillis(shiftedInstant, request.input()),
                shiftedInstant.getEpochSecond(),
                DateTimeFormatter.ISO_INSTANT.format(shiftedInstant),
                zoneId.getId(),
                format(shifted, request.customPattern()),
                amount,
                unit
        );
    }

    /**
     * Adds or subtracts a signed number of business days in a target timezone, skipping
     * Saturdays, Sundays and any caller-supplied holiday date.
     *
     * <p>The cursor is a {@link ZonedDateTime} stepped with {@link ZonedDateTime#plusDays(long)},
     * so the local wall-clock time is preserved across DST gaps/overlaps. Holidays are interpreted
     * as calendar dates in the target zone. A holiday that falls on a weekend counts only as a
     * weekend skip. The method is a pure function of its request: no clock, no ambient default
     * zone and no mutable state, so a journalled call replays deterministically.</p>
     */
    public DateTimeBusinessDaysResponse businessDays(DateTimeBusinessDaysRequest request) {
        if (request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("input datetime or timestamp is required");
        }

        Integer requestedAmount = request.amount();
        if (requestedAmount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        int amount = requestedAmount;
        if (amount < -MAX_BUSINESS_DAY_AMOUNT || amount > MAX_BUSINESS_DAY_AMOUNT) {
            throw new IllegalArgumentException("amount must be between -10000 and 10000");
        }

        ZoneId zoneId = parseZoneId(request.targetTimeZone());
        Set<LocalDate> holidayDates = parseHolidays(request.holidays());

        Instant instant;
        try {
            instant = parseInstant(request.input());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unable to parse input datetime: " + e.getMessage(), e);
        }

        ZonedDateTime cursor = instant.atZone(zoneId);
        ZonedDateTime moved = cursor;
        int remaining = Math.abs(amount);
        int step = amount < 0 ? -1 : 1;
        int weekendSkips = 0;
        int holidaySkips = 0;
        int scanned = 0;

        while (remaining > 0) {
            if (++scanned > MAX_SCAN_DAYS) {
                throw new IllegalArgumentException("Business-day arithmetic out of range");
            }
            try {
                moved = moved.plusDays(step);
            } catch (DateTimeException | ArithmeticException e) {
                throw new IllegalArgumentException("Business-day arithmetic out of range", e);
            }
            LocalDate date = moved.toLocalDate();
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                weekendSkips++;
                continue;
            }
            if (holidayDates.contains(date)) {
                holidaySkips++;
                continue;
            }
            remaining--;
        }

        Instant result = moved.toInstant();

        return new DateTimeBusinessDaysResponse(
                toEpochMillis(result, request.input()),
                result.getEpochSecond(),
                DateTimeFormatter.ISO_INSTANT.format(result),
                zoneId.getId(),
                format(moved, request.customPattern()),
                amount,
                weekendSkips,
                holidaySkips
        );
    }

    /**
     * Parses or formats an ISO-8601 duration. {@code PARSE} accepts week/day/hour/minute/second
     * components with fractional seconds; {@code FORMAT} renders canonical ISO-8601 text from
     * signed seconds plus optional nanoseconds.
     *
     * <p>The method is a pure function of its request: no clock, no locale, no timezone database
     * and no mutable state. Throws {@link IllegalArgumentException} for every rejected input.</p>
     */
    public DateTimeDurationResponse duration(DateTimeDurationRequest request) {
        String action = request.action() == null ? "" : request.action().trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "PARSE" -> parseDuration(request.input());
            case "FORMAT" -> formatDuration(request.seconds(), request.nanos());
            default -> throw new IllegalArgumentException(
                    "Unsupported action: " + request.action() + ". Expected one of PARSE, FORMAT");
        };
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

    /**
     * Resolves the ISO-8601 week-based year and week of a calendar date.
     *
     * <p>Pure function: it reads no clock, default timezone or locale, relying only on
     * {@link LocalDate} parsing, {@link WeekFields#ISO} and {@link DayOfWeek#name()}.
     * Throws {@link IllegalArgumentException} for a null, blank or malformed date.
     */
    public DateTimeWeekResponse week(String date) {
        LocalDate parsed = parseWeekDate(date);
        DayOfWeek dayOfWeek = parsed.getDayOfWeek();
        return new DateTimeWeekResponse(
                parsed.toString(),
                parsed.get(WeekFields.ISO.weekBasedYear()),
                parsed.get(WeekFields.ISO.weekOfWeekBasedYear()),
                dayOfWeek.name(),
                parsed.getDayOfYear(),
                dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY);
    }

    /**
     * Enumerates the calendar dates between two ISO dates as an ordered, bounded list.
     *
     * <p>Point {@code i} is always {@code startDate.plus((long) i * step, unit)}, never the previous point
     * plus one step, so month-end and leap-day clamping stay anchored to {@code startDate}:
     * {@code 2026-01-31} with {@code MONTHS} yields {@code 2026-02-28} then {@code 2026-03-31}, and
     * {@code 2024-02-29} with {@code YEARS} yields {@code 2025-02-28}.
     * With {@code inclusive = true} the last emitted point may equal {@code endDate} (so
     * {@code startDate == endDate} yields a single date); with {@code inclusive = false} the end date is
     * omitted (so {@code startDate == endDate} yields an empty list).</p>
     *
     * <p>Pure function: no clock, no locale-sensitive formatting, no mutable state. Every rejection is an
     * {@link IllegalArgumentException} - an unparseable date, {@code startDate} after {@code endDate}, a
     * {@code step} outside 1..1000, an unsupported unit, calendar overflow and a 1001st in-range point -
     * so a bad request surfaces as a 400 and never as a 500.</p>
     */
    public DateTimeRangeResponse range(DateTimeRangeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        LocalDate start = parseRangeDate("startDate", request.startDate());
        LocalDate end = parseRangeDate("endDate", request.endDate());
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate must not be after endDate: " + start + " > " + end);
        }
        ChronoUnit unit = resolveRangeUnit(request.unit());
        int step = resolveRangeStep(request.step());
        boolean inclusive = request.inclusive() == null || request.inclusive();

        List<String> dates = new ArrayList<>();
        for (int index = 0; ; index++) {
            LocalDate point;
            try {
                point = start.plus((long) index * step, unit);
            } catch (DateTimeException | ArithmeticException e) {
                throw new IllegalArgumentException("Date range is outside the supported calendar range", e);
            }
            if (inclusive ? point.isAfter(end) : !point.isBefore(end)) {
                break;
            }
            if (dates.size() == MAX_RANGE_POINTS) {
                throw new IllegalArgumentException("Date range must produce at most " + MAX_RANGE_POINTS
                        + " dates: narrow the range or increase step");
            }
            dates.add(point.toString());
        }
        return new DateTimeRangeResponse(start.toString(), end.toString(), unit.name(), step, inclusive,
                dates.size(), List.copyOf(dates));
    }

    /**
     * Parses an untrusted range boundary as an ISO local date, mapping every rejection - including
     * {@code null}, blank and {@link java.time.DateTimeException} - to {@link IllegalArgumentException}.
     */
    private LocalDate parseRangeDate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a valid yyyy-MM-dd date: " + value);
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    field + " must be a valid yyyy-MM-dd date: " + abbreviate(trimmed), e);
        }
    }

    /**
     * Resolves the optional unit name: blank means {@link ChronoUnit#DAYS}, matching is case-insensitive and
     * only date-based units are accepted.
     */
    private ChronoUnit resolveRangeUnit(String unit) {
        String raw = unit == null ? "" : unit.trim();
        if (raw.isEmpty()) {
            return ChronoUnit.DAYS;
        }
        try {
            ChronoUnit resolved = ChronoUnit.valueOf(raw.toUpperCase(Locale.ROOT));
            if (resolved == ChronoUnit.DAYS || resolved == ChronoUnit.WEEKS
                    || resolved == ChronoUnit.MONTHS || resolved == ChronoUnit.YEARS) {
                return resolved;
            }
        } catch (IllegalArgumentException e) {
            // Fall through to the single rejection below so every bad unit reads the same.
        }
        throw new IllegalArgumentException("unit must be one of DAYS, WEEKS, MONTHS, YEARS: " + abbreviate(raw));
    }

    /** Resolves the optional stride: {@code null} means 1, otherwise 1..1000. */
    private int resolveRangeStep(Integer step) {
        if (step == null) {
            return 1;
        }
        if (step < 1 || step > MAX_RANGE_POINTS) {
            throw new IllegalArgumentException("step must be between 1 and 1000: " + step);
        }
        return step;
    }

    private DateTimeDurationResponse parseDuration(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Duration input cannot be empty");
        }
        if (trimmed.length() > MAX_DURATION_INPUT_LENGTH) {
            throw new IllegalArgumentException(
                    "Duration input is too long (max " + MAX_DURATION_INPUT_LENGTH + " characters)");
        }

        boolean negative = false;
        String magnitude = trimmed;
        if (magnitude.startsWith("-")) {
            negative = true;
            magnitude = magnitude.substring(1);
        } else if (magnitude.startsWith("+")) {
            magnitude = magnitude.substring(1);
        }

        if (CALENDAR_UNIT.matcher(magnitude).find()) {
            throw new IllegalArgumentException(
                    "Duration uses unsupported calendar unit (years or months are not allowed): " + trimmed);
        }

        Matcher m = ISO_DURATION.matcher(magnitude);
        boolean anyComponent = m.matches()
                && (m.group(1) != null || m.group(2) != null || m.group(3) != null
                || m.group(4) != null || m.group(5) != null);
        if (!anyComponent) {
            throw new IllegalArgumentException("Unable to parse ISO-8601 duration: " + trimmed);
        }
        if (m.group(6) != null && m.group(6).length() > 9) {
            throw new IllegalArgumentException(
                    "Duration fractional seconds are limited to nanosecond precision");
        }

        long total;
        int nanos;
        try {
            long weeks = component(m.group(1));
            long days = component(m.group(2));
            long hours = component(m.group(3));
            long minutes = component(m.group(4));
            long whole = component(m.group(5));
            nanos = m.group(6) == null ? 0
                    : Integer.parseInt((m.group(6) + "000000000").substring(0, 9));
            total = Math.multiplyExact(weeks, SECONDS_PER_WEEK);
            total = Math.addExact(total, Math.multiplyExact(days, SECONDS_PER_DAY));
            total = Math.addExact(total, Math.multiplyExact(hours, 3_600L));
            total = Math.addExact(total, Math.multiplyExact(minutes, 60L));
            total = Math.addExact(total, whole);
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Duration out of range: " + trimmed, e);
        }

        return buildDurationResponse("PARSE", input, negative, total, nanos);
    }

    private DateTimeDurationResponse formatDuration(Long seconds, Integer nanos) {
        if (seconds == null) {
            throw new IllegalArgumentException("seconds is required when action is FORMAT");
        }
        int fraction = nanos == null ? 0 : nanos;
        if (fraction < 0 || fraction > 999_999_999) {
            throw new IllegalArgumentException("nanos must be between 0 and 999999999");
        }
        if (seconds == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Duration out of range: " + seconds);
        }
        boolean negative = seconds < 0;
        long magnitude = negative ? -seconds : seconds;
        return buildDurationResponse("FORMAT", null, negative, magnitude, fraction);
    }

    /** Builds the sign-magnitude response: {@code nanos} is always the magnitude of the fraction. */
    private DateTimeDurationResponse buildDurationResponse(
            String action, String input, boolean negative, long magnitudeSeconds, int nanos) {
        long days = magnitudeSeconds / SECONDS_PER_DAY;
        long remainder = magnitudeSeconds % SECONDS_PER_DAY;
        long hours = remainder / 3_600L;
        long minutes = (remainder % 3_600L) / 60L;
        long seconds = remainder % 60L;
        String iso = toIso(negative, magnitudeSeconds, nanos);
        long totalSeconds = negative ? -magnitudeSeconds : magnitudeSeconds;
        return new DateTimeDurationResponse(
                action,
                input,
                iso,
                negative,
                days,
                (int) hours,
                (int) minutes,
                (int) seconds,
                nanos,
                totalSeconds);
    }

    private String toIso(boolean negative, long magnitudeSeconds, int nanos) {
        long d = magnitudeSeconds / SECONDS_PER_DAY;
        long rem = magnitudeSeconds % SECONDS_PER_DAY;
        long h = rem / 3_600L;
        long mi = (rem % 3_600L) / 60L;
        long s = rem % 60L;
        boolean hasTime = h > 0 || mi > 0 || s > 0 || nanos > 0;
        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('-');
        }
        sb.append('P');
        if (d > 0) {
            sb.append(d).append('D');
        }
        if (hasTime) {
            sb.append('T');
            if (h > 0) {
                sb.append(h).append('H');
            }
            if (mi > 0) {
                sb.append(mi).append('M');
            }
            if (s > 0 || nanos > 0) {
                sb.append(s);
                if (nanos > 0) {
                    String fraction = String.format("%09d", nanos).replaceAll("0+$", "");
                    sb.append('.').append(fraction);
                }
                sb.append('S');
            }
        }
        if (!hasTime && d == 0) {
            sb.append("T0S");
        }
        return sb.toString();
    }

    private static long component(String value) {
        return value == null ? 0L : Long.parseLong(value);
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

    /**
     * Same guard as {@link #toEpochMillis(Instant, String, String)} for call sites that take no
     * {@code inputFormat} (the {@code shift} path), so a shift landing inside the {@code ZonedDateTime}
     * year range but outside the {@code long} millisecond range is a 400 {@code BAD_REQUEST} rather
     * than an {@link ArithmeticException} escaping to a 500.
     */
    private long toEpochMillis(Instant instant, String originalInput) {
        return toEpochMillis(instant, originalInput, null);
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

    /**
     * Parses the optional caller-supplied holiday list into a deduplicated set of ISO local dates.
     * Duplicates collapse, ordering stays deterministic and every malformed entry is a 400.
     */
    private Set<LocalDate> parseHolidays(List<String> holidays) {
        if (holidays == null || holidays.isEmpty()) {
            return Set.of();
        }
        if (holidays.size() > MAX_HOLIDAY_ENTRIES) {
            throw new IllegalArgumentException("holidays must contain at most 366 entries");
        }

        Set<LocalDate> dates = new LinkedHashSet<>();
        for (String holiday : holidays) {
            String trimmed = holiday == null ? "" : holiday.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("holiday entries must not be blank");
            }
            try {
                dates.add(LocalDate.parse(trimmed));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid holiday date: " + trimmed
                        + ". Expected ISO-8601 local date (yyyy-MM-dd)", e);
            }
        }
        return dates;
    }

    /**
     * Parses the untrusted {@code date} query value as an ISO local date, mapping every
     * rejection to {@link IllegalArgumentException} so it surfaces as a 400, never a 500.
     */
    private LocalDate parseWeekDate(String date) {
        String trimmed = date == null ? "" : date.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("date is required: expected " + EXPECTED_WEEK_DATE);
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "Invalid date: " + abbreviate(trimmed) + ". Expected " + EXPECTED_WEEK_DATE, e);
        }
    }

    private String abbreviate(String value) {
        return value.length() <= MAX_WEEK_DATE_ECHO ? value : value.substring(0, MAX_WEEK_DATE_ECHO);
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
