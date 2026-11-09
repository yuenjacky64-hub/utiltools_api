package com.example.utiltools.datetime.controller;

import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysRequest;
import com.example.utiltools.datetime.dto.DateTimeBusinessDaysResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeDurationRequest;
import com.example.utiltools.datetime.dto.DateTimeDurationResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
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
}
