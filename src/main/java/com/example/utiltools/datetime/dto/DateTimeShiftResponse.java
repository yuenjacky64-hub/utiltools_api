package com.example.utiltools.datetime.dto;

public record DateTimeShiftResponse(
        long epochMillis,
        long epochSeconds,
        String isoUtc,
        String targetTimeZone,
        String formattedTarget,
        long appliedAmount,
        String appliedUnit
) {}
