package com.example.utiltools.datetime.dto;

public record TimeZoneInfo(
        String timeZone,
        String offset,
        int offsetSeconds,
        String localDateTime
) {}
