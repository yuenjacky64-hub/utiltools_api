package com.example.utiltools.json.dto;

import java.time.Instant;

/**
 * The JSONPath query result. {@code staleFallback}, {@code cachedAt} and {@code cacheAgeMillis}
 * are additive and populated only when the response was served from the last-known-good cache
 * during an open circuit; a live result leaves all three null.
 */
public record JsonQueryResponse(
        String jsonPath,
        Object result,
        Boolean staleFallback,
        Instant cachedAt,
        Long cacheAgeMillis
) {

    public JsonQueryResponse(String jsonPath, Object result) {
        this(jsonPath, result, null, null, null);
    }

    /** Mark a cached live result as a stale fallback, preserving its payload and age. */
    public static JsonQueryResponse stale(JsonQueryResponse live, Instant cachedAt, long cacheAgeMillis) {
        return new JsonQueryResponse(live.jsonPath(), live.result(), Boolean.TRUE, cachedAt, cacheAgeMillis);
    }
}
