package com.example.utiltools.datetime.dto;

import java.util.List;

/**
 * The expanded cron schedule. Every field echoes a resolved request value, so the payload is
 * a pure function of the request and replays byte-identically.
 *
 * @param expression  the cron expression as parsed, trimmed
 * @param from        the anchor datetime exactly as requested; occurrences are strictly after it
 * @param timeZone    resolved zone id (e.g. "UTC"), never null
 * @param count       resolved count, never 0 ({@code 5} when the request omitted it)
 * @param runs        ordered, immutable occurrences, exactly {@code count} entries
 */
public record DateTimeCronResponse(
        String expression,
        String from,
        String timeZone,
        int count,
        List<DateTimeCronOccurrence> runs
) {
    /**
     * One occurrence of the schedule, resolved through the requested zone so DST
     * transitions keep the wall-clock hour correct.
     *
     * @param utcIsoInstant UTC instant, ISO-8601, e.g. {@code 2026-01-01T12:00:00Z}
     * @param localDateTime the same instant in the requested zone, ISO-8601 offset date-time
     * @param epochMillis   the instant in epoch milliseconds
     */
    public record DateTimeCronOccurrence(
            String utcIsoInstant,
            String localDateTime,
            long epochMillis
    ) {}
}
