package com.example.utiltools.datetime.dto;

/**
 * Half-open [start, end) intersection of two datetime intervals.
 * {@code overlapStart}/{@code overlapEnd} are ISO-8601 UTC strings and
 * {@code overlapMillis} is 0 when the intervals are disjoint.
 */
public record DateTimeOverlapResponse(
        boolean overlap,
        String overlapStart,
        String overlapEnd,
        long overlapMillis
) {}
