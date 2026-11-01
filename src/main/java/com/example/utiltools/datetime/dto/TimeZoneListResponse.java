package com.example.utiltools.datetime.dto;

import java.util.List;

public record TimeZoneListResponse(
        String prefix,
        int limit,
        int total,
        List<TimeZoneInfo> timeZones
) {}
