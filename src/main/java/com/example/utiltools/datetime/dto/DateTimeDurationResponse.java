package com.example.utiltools.datetime.dto;

public record DateTimeDurationResponse(
        String action,
        String input,
        String iso,
        boolean negative,
        long days,
        int hours,
        int minutes,
        int seconds,
        int nanos,
        long totalSeconds
) {}
