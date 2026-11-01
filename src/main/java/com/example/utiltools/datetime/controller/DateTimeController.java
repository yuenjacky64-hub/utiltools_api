package com.example.utiltools.datetime.controller;

import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.datetime.dto.CurrentTimeResponse;
import com.example.utiltools.datetime.dto.DateTimeConvertRequest;
import com.example.utiltools.datetime.dto.DateTimeConvertResponse;
import com.example.utiltools.datetime.dto.DateTimeDiffRequest;
import com.example.utiltools.datetime.dto.DateTimeDiffResponse;
import com.example.utiltools.datetime.dto.DateTimeShiftRequest;
import com.example.utiltools.datetime.dto.DateTimeShiftResponse;
import com.example.utiltools.datetime.service.DateTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    private final DateTimeService dateTimeService;

    public DateTimeController(DateTimeService dateTimeService) {
        this.dateTimeService = dateTimeService;
    }

    @PostMapping("/convert")
    @Operation(summary = "Convert datetime/timestamp across timezones", description = "Converts epoch millis/seconds or ISO-8601 strings into target timezone with custom formatting")
    public ApiResponse<DateTimeConvertResponse> convert(@Valid @RequestBody DateTimeConvertRequest request) {
        DateTimeConvertResponse result = dateTimeService.convert(request);
        return ApiResponse.success(result, "DateTime converted successfully");
    }

    @PostMapping("/diff")
    @Operation(summary = "Calculate difference between two datetimes", description = "Computes duration in days, hours, minutes, seconds and readable text")
    public ApiResponse<DateTimeDiffResponse> diff(@Valid @RequestBody DateTimeDiffRequest request) {
        DateTimeDiffResponse result = dateTimeService.diff(request);
        return ApiResponse.success(result, "DateTime difference calculated successfully");
    }

    @PostMapping("/shift")
    @Operation(summary = "Shift datetime by a signed amount and unit in a target timezone", description = "Applies years/months/weeks/days/hours/minutes/seconds arithmetic with java.time ZonedDateTime semantics, including month-end clamping and DST transitions")
    public ApiResponse<DateTimeShiftResponse> shift(@Valid @RequestBody DateTimeShiftRequest request) {
        DateTimeShiftResponse result = dateTimeService.shift(request);
        return ApiResponse.success(result, "DateTime shifted successfully");
    }

    @GetMapping("/current")
    @Operation(summary = "Get current time in specified timezone", description = "Returns UTC and local timestamp with milliseconds")
    public ApiResponse<CurrentTimeResponse> current(
            @RequestParam(value = "timeZone", required = false, defaultValue = "UTC") String timeZone) {
        CurrentTimeResponse result = dateTimeService.getCurrentTime(timeZone);
        return ApiResponse.success(result, "Current time retrieved successfully");
    }
}
