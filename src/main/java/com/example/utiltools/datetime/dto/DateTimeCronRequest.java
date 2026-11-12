package com.example.utiltools.datetime.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cron next-run expansion request. {@code expression} accepts Spring's cron grammar:
 * 5-field and 6-field forms plus {@code @} macros (e.g. {@code @daily}); {@code from} uses
 * the same auto-detected epoch/ISO grammar as the other datetime routes; a blank or absent
 * {@code timeZone} resolves to UTC; an absent {@code count} resolves to 5.
 */
public record DateTimeCronRequest(
        @NotBlank(message = "cron expression is required")
        @Size(max = 128, message = "cron expression must be at most 128 characters")
        String expression,
        @NotBlank(message = "from datetime is required")
        String from,
        String timeZone,
        @Min(value = 1, message = "count must be between 1 and 100")
        @Max(value = 100, message = "count must be between 1 and 100")
        Integer count
) {}
