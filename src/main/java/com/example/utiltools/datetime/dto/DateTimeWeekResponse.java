package com.example.utiltools.datetime.dto;

public record DateTimeWeekResponse(
        String date,
        int isoWeekYear,
        int isoWeek,
        String dayOfWeek,
        int dayOfYear,
        boolean weekend
) {}
