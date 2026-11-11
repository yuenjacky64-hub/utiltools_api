package com.example.utiltools.datetime.dto;

import java.util.List;

/**
 * The generated calendar date range. Every field echoes a resolved request value, so the payload is a pure
 * function of the request and replays byte-identically.
 *
 * @param startDate parsed start date, {@code yyyy-MM-dd}
 * @param endDate   parsed end date, {@code yyyy-MM-dd}
 * @param unit      resolved unit, never blank ({@code DAYS} when the request omitted it)
 * @param step      resolved step, never {@code null} ({@code 1} when the request omitted it)
 * @param inclusive resolved end-inclusivity, never {@code null} ({@code true} when the request omitted it)
 * @param count     number of generated dates, equal to {@code dates.size()}
 * @param dates     ordered, immutable {@code yyyy-MM-dd} dates, at most 1000 entries
 */
public record DateTimeRangeResponse(
        String startDate,
        String endDate,
        String unit,
        int step,
        boolean inclusive,
        int count,
        List<String> dates
) {}
