package com.example.utiltools.json.service;

import com.example.utiltools.json.dto.JsonQueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, TTL-based last-known-good store for successful {@code JSON_QUERY} results.
 *
 * <p>Entries are keyed by a SHA-256 digest of the normalized {@code json} plus {@code jsonPath}
 * pair, so two requests whose documents differ only in whitespace or formatting share one entry.
 * The cache is a side structure: it never calls the circuit breaker and never stores a response
 * that was itself served as a stale fallback, so a fallback can never extend its own TTL.
 *
 * <p>Every interaction is best effort — the caller wraps the public methods in a
 * {@code try/catch (RuntimeException)} — and every map access sits inside a {@code synchronized}
 * method while the counters are {@link AtomicLong}s, so no lock is held while JSONPath or the
 * breaker runs.
 */
public class JsonQueryFallbackCache {

    private static final long DEFAULT_TTL_MS = 60_000L;
    private static final int DEFAULT_MAX_ENTRIES = 256;
    private static final char KEY_SEPARATOR = '\u0000';

    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public JsonQueryFallbackCache(Duration ttl, int maxEntries, Clock clock, ObjectMapper objectMapper) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * The same defaults the Spring bean uses, so the two pre-existing {@code JsonService}
     * constructors keep working with a private cache (mirrors {@code CircuitBreakerConfig.defaults()}).
     */
    public static JsonQueryFallbackCache defaults() {
        return new JsonQueryFallbackCache(Duration.ofMillis(DEFAULT_TTL_MS), DEFAULT_MAX_ENTRIES,
                Clock.systemUTC(), new ObjectMapper());
    }

    /**
     * Content-derived key. Empty when the pair is not cacheable (null or blank member), which the
     * caller treats as "never remembered, never served".
     */
    public Optional<String> keyFor(String json, String jsonPath) {
        if (json == null || json.isBlank() || jsonPath == null || jsonPath.isBlank()) {
            return Optional.empty();
        }
        String material = canonical(json) + KEY_SEPARATOR + jsonPath.trim();
        return Optional.of(sha256Hex(material));
    }

    /**
     * Stores a successful live response. No-op for {@code null} or already-stale values, so a
     * fallback is never re-inserted. Re-warming an existing key counts as newest.
     */
    public void remember(String json, String jsonPath, JsonQueryResponse live) {
        if (live == null || Boolean.TRUE.equals(live.staleFallback())) {
            return;
        }
        Optional<String> key = keyFor(json, jsonPath);
        if (key.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        synchronized (this) {
            entries.remove(key.get());
            entries.put(key.get(), new Entry(live, now));
            while (entries.size() > maxEntries) {
                String oldest = entries.keySet().iterator().next();
                entries.remove(oldest);
                evictions.incrementAndGet();
            }
        }
    }

    /**
     * Reads a fresh entry as a stale-marked copy. An expired entry is dropped and counted as
     * expired (not as a miss); an absent key is a miss. Never writes an entry.
     */
    public Optional<JsonQueryResponse> serve(String json, String jsonPath) {
        Optional<String> key = keyFor(json, jsonPath);
        if (key.isEmpty()) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        Instant now = clock.instant();
        Entry entry;
        synchronized (this) {
            entry = entries.get(key.get());
            if (entry == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            if (isExpired(entry, now)) {
                entries.remove(key.get());
                expired.incrementAndGet();
                return Optional.empty();
            }
        }
        hits.incrementAndGet();
        long ageMillis = Math.max(0L, Duration.between(entry.cachedAt(), now).toMillis());
        return Optional.of(JsonQueryResponse.stale(entry.response(), entry.cachedAt(), ageMillis));
    }

    /** Removes every expired entry and returns how many were dropped. */
    public int sweepExpired() {
        Instant now = clock.instant();
        int removed = 0;
        synchronized (this) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (isExpired(iterator.next().getValue(), now)) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            expired.addAndGet(removed);
        }
        return removed;
    }

    public Stats stats() {
        synchronized (this) {
            return new Stats(hits.get(), misses.get(), expired.get(), evictions.get(), entries.size(),
                    ttl.toMillis(), maxEntries);
        }
    }

    private boolean isExpired(Entry entry, Instant now) {
        return !Duration.between(entry.cachedAt(), now).minus(ttl).isNegative();
    }

    /**
     * Formatting-insensitive form of the document: parsed and re-serialized when it parses (field
     * order preserved), otherwise the trimmed raw text.
     */
    private String canonical(String json) {
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(json));
        } catch (Exception e) {
            return json.trim();
        }
    }

    private static String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private record Entry(JsonQueryResponse response, Instant cachedAt) {}

    /** Bounded snapshot of the cache counters plus its configured bounds. */
    public record Stats(long hits, long misses, long expired, long evictions, int size,
                        long ttlMillis, int maxEntries) {}
}
