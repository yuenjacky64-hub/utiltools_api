package com.example.utiltools.datetime.dto;

public record DateTimeDiffResponse(
        long days,
        long hours,
        long minutes,
        long seconds,
        long millis,
        String humanReadable
) {}
