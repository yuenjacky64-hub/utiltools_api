package com.example.utiltools.datetime.dto;

public record DateTimeBusinessDaysResponse(
        long epochMillis,
        long epochSeconds,
        String isoUtc,
        String targetTimeZone,
        String formattedTarget,
        int appliedAmount,
        int skippedWeekendDays,
        int skippedHolidayDays
) {}
