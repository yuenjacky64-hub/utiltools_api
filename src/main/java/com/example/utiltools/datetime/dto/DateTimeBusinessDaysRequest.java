package com.example.utiltools.datetime.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DateTimeBusinessDaysRequest(
        @NotBlank(message = "input datetime or timestamp is required")
        String input,
        String targetTimeZone,
        @NotNull(message = "amount is required")
        @Min(value = -10000, message = "amount must be between -10000 and 10000")
        @Max(value = 10000, message = "amount must be between -10000 and 10000")
        Integer amount,
        String customPattern,
        @Size(max = 366, message = "holidays must contain at most 366 entries")
        List<String> holidays
) {}
