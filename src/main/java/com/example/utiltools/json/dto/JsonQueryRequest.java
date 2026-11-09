package com.example.utiltools.json.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A JSONPath query. {@code allowStaleFallback} opts the request into the last-known-good fallback
 * cache: when the shared JSONPath breaker is open the caller receives a cached, explicitly stale
 * result instead of 503 {@code CIRCUIT_OPEN}. It defaults to {@code false}, so the two-argument
 * constructor keeps the previous fail-closed behaviour.
 */
public record JsonQueryRequest(
        @NotBlank(message = "json content is required")
        String json,
        @NotBlank(message = "jsonPath expression is required (e.g. $.store.books[*].title)")
        String jsonPath,
        Boolean allowStaleFallback
) {

    public JsonQueryRequest(String json, String jsonPath) {
        this(json, jsonPath, null);
    }

    public boolean staleFallbackRequested() {
        return Boolean.TRUE.equals(allowStaleFallback);
    }

    /**
     * The same query without the opt-in flag, used when replaying a stored request so a replay can
     * never serve stale data or emit fallback metadata.
     */
    public JsonQueryRequest liveOnly() {
        return new JsonQueryRequest(json, jsonPath, null);
    }
}
