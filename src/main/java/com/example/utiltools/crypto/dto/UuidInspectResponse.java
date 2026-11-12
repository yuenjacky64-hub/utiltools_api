package com.example.utiltools.crypto.dto;

public record UuidInspectResponse(
        String canonical,
        String compact,
        int version,
        String variant,
        Long unixTimestampMillis
) {}
