package com.example.utiltools.datetime.dto;

public record CurrentTimeResponse(
        long epochMillis,
        long epochSeconds,
        String utc,
        String local,
        String timeZone
) {}
