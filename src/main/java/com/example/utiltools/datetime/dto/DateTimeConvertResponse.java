package com.example.utiltools.datetime.dto;

public record DateTimeConvertResponse(
        long epochMillis,
        long epochSeconds,
        String isoUtc,
        String targetTimeZone,
        String formattedTarget
) {}
