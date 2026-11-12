package com.example.utiltools.datetime.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysRequest;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeCronRequest;
import com.example.utiltools.datetime.dto.DateTimeCronResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeOverlapRequest;
import com.example.utiltools.datetime.dto.DateTimeOverlapResponse;
import com.example.utiltools.datetime.dto.DateTimeDurationRequest;
import com.example.utiltools.datetime.dto.DateTimeDurationResponse;
import com.example.utiltools.datetime.dto.DateTimeRangeRequest;
import com.example.utiltools.datetime.dto.DateTimeRangeResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import com.example.utiltools.datetime.dto.DateTimeWeekResponse;
import com.example.utiltools.datetime.dto.TimeZoneListResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/datetime")
@Tag(name = "DateTime Utility", description = "Endpoints for timezone conversion, duration calculation, and current time")
public class DateTimeController {

    private final AuditedOperations auditedOperations;
    private final DateTimeService dateTimeService;

    public DateTimeController(AuditedOperations auditedOperations, DateTimeService dateTimeService) {
        this.auditedOperations = auditedOperations;
        this.dateTimeService = dateTimeService;
    }

    @PostMapping("/convert")
    @Operation(summary = "Convert datetime/timestamp across timezones", description = "Converts epoch millis/seconds or ISO-8601 strings into target timezone with custom formatting")
    public ApiResponse<DateTimeConvertResponse> convert(@Valid @RequestBody DateTimeConvertRequest request) {
        DateTimeConvertResponse result = auditedOperations.convert(request);
        return ApiResponse.success(result, "DateTime converted successfully");
    }

    @PostMapping("/diff")
    @Operation(summary = "Calculate difference between two datetimes", description = "Computes duration in days, hours, minutes, seconds and readable text")
    public ApiResponse<DateTimeDiffResponse> diff(@Valid @RequestBody DateTimeDiffRequest request) {
        DateTimeDiffResponse result = auditedOperations.diff(request);
        return ApiResponse.success(result, "DateTime difference calculated successfully");
    }

    @PostMapping("/shift")
    @Operation(summary = "Shift datetime by a signed amount and unit in a target timezone", description = "Applies years/months/weeks/days/hours/minutes/seconds arithmetic with java.time ZonedDateTime semantics, including month-end clamping and DST transitions")
    public ApiResponse<DateTimeShiftResponse> shift(@Valid @RequestBody DateTimeShiftRequest request) {
        DateTimeShiftResponse result = auditedOperations.shift(request);
        return ApiResponse.success(result, "DateTime shifted successfully");
    }

    @PostMapping("/duration")
    @Operation(summary = "Parse or format ISO-8601 durations",
            description = "Parses week/day/hour/minute/second ISO-8601 durations such as P1DT2H30M into components and total seconds, or formats signed seconds and nanos back into canonical ISO-8601 text")
    public ApiResponse<DateTimeDurationResponse> duration(@Valid @RequestBody DateTimeDurationRequest request) {
        DateTimeDurationResponse result = auditedOperations.duration(request);
        return ApiResponse.success(result, "DateTime duration processed successfully");
    }

    @PostMapping("/business-days")
    @Operation(summary = "Add or subtract business days",
            description = "Steps a signed amount of business days (Saturdays and Sundays skipped) in "
                    + "targetTimeZone, additionally skipping any date in the optional ISO yyyy-MM-dd "
                    + "holidays list. Local wall-clock time is preserved across DST transitions. "
                    + "amount is bounded to -10000..10000; amount 0 returns the input instant unchanged. "
                    + "Weekend holidays count only as weekend skips.")
    public ResponseEntity<ApiResponse<DateTimeBusinessDaysResponse>> businessDays(
            @Valid @RequestBody DateTimeBusinessDaysRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                auditedOperations.businessDays(request), "Business days calculated successfully"));
    }

    @PostMapping("/range")
    @Operation(summary = "Generate a bounded calendar date range",
            description = "Returns an ordered list of yyyy-MM-dd dates from startDate towards endDate in "
                    + "DAYS/WEEKS/MONTHS/YEARS steps, anchored to startDate (month-end and leap-day clamped), "
                    + "with an optional inclusive end and a hard bound of 1000 dates")
    public ApiResponse<DateTimeRangeResponse> range(@Valid @RequestBody DateTimeRangeRequest request) {
        DateTimeRangeResponse result = auditedOperations.range(request);
        return ApiResponse.success(result, "Date range generated successfully");
    }

    @PostMapping("/overlap")
    @Operation(summary = "Detect the overlap of two datetime intervals",
            description = "Treats both intervals as half-open [start, end). When they intersect, the "
                    + "response carries the UTC intersection bounds and its duration in milliseconds; when "
                    + "they are disjoint (including the adjacent case endA == startB) it carries "
                    + "overlap=false, null bounds and overlapMillis=0. Each bound accepts the same "
                    + "ISO-8601 and epoch seconds/millis auto-detected inputs as /datetime/diff; an "
                    + "interval whose end is not strictly after its start returns 400 BAD_REQUEST")
    public ApiResponse<DateTimeOverlapResponse> overlap(@Valid @RequestBody DateTimeOverlapRequest request) {
        DateTimeOverlapResponse result = auditedOperations.overlap(request);
        return ApiResponse.success(result, "DateTime overlap calculated successfully");
    }

    @PostMapping("/cron/next")
    @Operation(summary = "Expand a cron schedule into its next occurrences",
            description = "Returns exactly count occurrences strictly after from, computed with Spring's "
                    + "CronExpression in the requested IANA zone. Accepts 5-field, 6-field and @macro "
                    + "expressions; occurrences are ordered and DST-aware; count is bounded to 1..100 "
                    + "(default 5)")
    public ApiResponse<DateTimeCronResponse> cronNext(@Valid @RequestBody DateTimeCronRequest request) {
        DateTimeCronResponse result = auditedOperations.cronNext(request);
        return ApiResponse.success(result, "Cron schedule expanded successfully");
    }

    @GetMapping("/current")
    @Operation(summary = "Get current time in specified timezone", description = "Returns UTC and local timestamp with milliseconds")
    public ApiResponse<CurrentTimeResponse> current(
            @RequestParam(value = "timeZone", required = false, defaultValue = "UTC") String timeZone) {
        CurrentTimeResponse result = auditedOperations.current(timeZone);
        return ApiResponse.success(result, "Current time retrieved successfully");
    }

    @GetMapping("/timezones")
    @Operation(summary = "List supported IANA timezones", description = "Returns a bounded, lexicographically sorted catalog of IANA timezone IDs with current UTC offset and local datetime; optionally filtered by case-insensitive prefix")
    public ApiResponse<TimeZoneListResponse> timezones(
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        TimeZoneListResponse result = dateTimeService.listTimeZones(prefix, limit);
        return ApiResponse.success(result, "Timezones retrieved successfully");
    }

    @GetMapping("/week")
    @Operation(summary = "Resolve the ISO-8601 week-year and week of a calendar date",
            description = "Computes WeekFields.ISO week-based year, week of week-based year, day of week, "
                    + "day of year and weekend flag for a yyyy-MM-dd date, including week-year rollover "
                    + "(2024-12-30 is 2025-W01). Read-only: not journalled.")
    public ApiResponse<DateTimeWeekResponse> week(
            @RequestParam(value = "date", required = false) String date) {
        DateTimeWeekResponse result = dateTimeService.week(date);
        return ApiResponse.success(result, "ISO week retrieved successfully");
    }

    @GetMapping("/week-date")
    @Operation(summary = "Resolve an ISO week back to a calendar date",
            description = "Resolves an ISO week-year, week and optional day (MONDAY..SUNDAY case-insensitive "
                    + "or 1..7, default MONDAY) to its calendar date anchored on WeekFields.ISO / January 4, "
                    + "returning date, ISO week-year, ISO week, day of week, day of year and weekend flag. "
                    + "Rejects a week that does not exist in that ISO week-year (e.g. 2021-W53). "
                    + "Read-only: not journalled.")
    public ApiResponse<DateTimeWeekResponse> weekDate(
            @RequestParam int year,
            @RequestParam int week,
            @RequestParam(value = "day", required = false, defaultValue = "") String day) {
        DateTimeWeekResponse result = dateTimeService.weekDate(year, week, day);
        return ApiResponse.success(result, "ISO week date retrieved successfully");
    }
}
