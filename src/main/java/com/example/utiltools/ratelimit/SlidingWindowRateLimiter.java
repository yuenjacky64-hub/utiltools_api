package com.example.utiltools.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * Thread-safe, clock-driven sliding-window rate limiter.
 *
 * <p>For every client key at most {@code requests-per-window} admission timestamps are kept in a
 * ring buffer sized exactly to the budget. Admission, purging, and counting happen under the
 * bucket lock, so a caller can never observe (or create) more than the configured number of
 * admissions inside {@code [now - windowMs, now]}. A denied call mutates nothing.</p>
 *
 * <p>Tracked keys are hard-bounded by {@code max-tracked-clients}: a new key evicts the oldest
 * bucket whose last event already left the window, and is rejected without insertion when every
 * tracked bucket is active.</p>
 *
 * <p>Lock order is always {@code mapLock} → bucket lock, never the reverse. Each eviction path
 * decides <em>and</em> removes inside one bucket-lock critical section (invariant I6), and the
 * admission path re-validates under the bucket lock that the instance it locked is still the mapped
 * one (invariant I8), so an admission can never be recorded into an orphaned bucket.</p>
 */
@Component
public class SlidingWindowRateLimiter {

    /** Error code carried in the unified {@code ApiResponse} envelope for a rejection. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    private static final String UNKNOWN_CLIENT = "unknown";

    /**
     * Bound on mapping re-validation retries. A retry is only possible when the bucket was evicted
     * between lookup and lock acquisition, which requires it to have held no in-window event, so the
     * loop converges immediately in practice.
     */
    private static final int MAX_MAPPING_ATTEMPTS = 8;

    /**
     * Outcome of one admission attempt.
     *
     * @param allowed           whether the request may proceed
     * @param limit             configured budget for the window
     * @param remaining         admitted requests still available in the window ({@code >= 0})
     * @param retryAfterSeconds seconds to wait before the oldest in-window admission expires
     *                          ({@code 0} when allowed, always {@code >= 1} when denied)
     * @param resetEpochSeconds epoch second at which the window frees up again
     */
    public record Admission(boolean allowed, long limit, long remaining,
                            long retryAfterSeconds, long resetEpochSeconds) {
    }

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ReentrantLock mapLock = new ReentrantLock();

    public SlidingWindowRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Attempts to admit one request for {@code clientKey}. The only write path of the limiter.
     *
     * @param clientKey client identity; {@code null}/blank is normalised to {@code "unknown"}
     * @return the admission outcome, never {@code null}
     */
    public Admission tryAcquire(String clientKey) {
        long limit = properties.requestsPerWindow();
        long windowMs = properties.windowMs();
        if (!properties.enabled()) {
            return new Admission(true, limit, limit, 0L, 0L);
        }

        String key = normaliseKey(clientKey);
        long now = clock.millis();

        for (int attempt = 0; attempt < MAX_MAPPING_ATTEMPTS; attempt++) {
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                bucket = registerBucket(key, now);
                if (bucket == null) {
                    // Every tracked bucket holds an in-window event: reject without inserting.
                    return new Admission(false, limit, 0L,
                            Math.max(1L, ceilDiv(windowMs, 1000L)),
                            ceilDiv(now + windowMs, 1000L));
                }
            }

            bucket.lock.lock();
            try {
                // I8: an eviction path may have removed this instance before the lock was acquired.
                // Writing to it would be lost, silently restoring consumed budget, so retry instead.
                if (buckets.get(key) != bucket) {
                    continue;
                }

                // Clock-regression guard: never move a bucket's window backwards.
                long effectiveNow = Math.max(now, bucket.lastEventMillis);
                purgeExpired(bucket, effectiveNow, windowMs);

                if (bucket.size >= limit) {
                    long resetAt = bucket.timestamps[bucket.start] + windowMs;
                    return new Admission(false, limit, 0L,
                            Math.max(1L, ceilDiv(resetAt - effectiveNow, 1000L)),
                            ceilDiv(resetAt, 1000L));
                }

                record(bucket, effectiveNow);
                bucket.lastEventMillis = effectiveNow;
                long remaining = Math.max(0L, limit - bucket.size);
                long resetAt = bucket.timestamps[bucket.start] + windowMs;
                return new Admission(true, limit, remaining, 0L, ceilDiv(resetAt, 1000L));
            } finally {
                bucket.lock.unlock();
            }
        }

        // Unreachable for any real clock: repeated eviction of a bucket that had no in-window event
        // loses no admission, so the fail-open branch keeps a live client from being penalised.
        return new Admission(true, limit, Math.max(0L, limit - 1L), 0L, ceilDiv(now + windowMs, 1000L));
    }

    /** Number of currently tracked client buckets (bounded by {@code max-tracked-clients}). */
    public int trackedClients() {
        return buckets.size();
    }

    /**
     * Removes buckets with no event inside the current window for at least two idle windows.
     *
     * @return the number of removed buckets
     */
    public int evictIdleBuckets() {
        long now = clock.millis();
        long idleThreshold = now - 2L * properties.windowMs();
        int removed = 0;
        mapLock.lock();
        try {
            for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
                Bucket bucket = entry.getValue();
                bucket.lock.lock();
                try {
                    // I6: the idle decision and the removal share one bucket-lock critical section,
                    // so no admission can record an in-window event in between.
                    if (bucket.lastEventMillis <= idleThreshold
                            && buckets.remove(entry.getKey(), bucket)) {
                        removed++;
                    }
                } finally {
                    bucket.lock.unlock();
                }
            }
        } finally {
            mapLock.unlock();
        }
        return removed;
    }

    /**
     * Removes at most one bucket whose last event already left the window: the oldest idle one.
     * This is the cap-eviction primitive used by {@link #registerBucket(String, long)} when the map
     * is saturated.
     *
     * @param now reference instant in epoch millis
     * @return {@code true} when a bucket was removed
     */
    boolean evictOldestExpiredBucket(long now) {
        mapLock.lock();
        try {
            return removeOldestExpiredBucketLocked(now);
        } finally {
            mapLock.unlock();
        }
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public RateLimitProperties properties() {
        return properties;
    }

    private Bucket registerBucket(String key, long now) {
        mapLock.lock();
        try {
            Bucket existing = buckets.get(key);
            if (existing != null) {
                return existing;
            }
            if (buckets.size() >= properties.maxTrackedClients()) {
                evictOldestExpiredBucket(now);   // reentrant mapLock
            }
            if (buckets.size() >= properties.maxTrackedClients()) {
                return null;
            }
            Bucket created = new Bucket(properties.requestsPerWindow());
            // Treat creation as activity so a concurrent cleanup can never drop a bucket
            // between insertion and its first recorded admission.
            created.lastEventMillis = now;
            buckets.put(key, created);
            return created;
        } finally {
            mapLock.unlock();
        }
    }

    /**
     * Removes the {@code mapLock}-protected oldest bucket that has no in-window event left. The
     * {@code lastEventMillis} sample and the removal are both taken under the candidate's bucket
     * lock, so a bucket that recorded an event in between is never evicted.
     */
    private boolean removeOldestExpiredBucketLocked(long now) {
        long expiredThreshold = now - properties.windowMs();
        String oldestKey = null;
        Bucket oldestBucket = null;
        long oldestLastEvent = Long.MAX_VALUE;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            bucket.lock.lock();
            try {
                long lastEvent = bucket.lastEventMillis;
                if (lastEvent <= expiredThreshold && lastEvent < oldestLastEvent) {
                    oldestLastEvent = lastEvent;
                    oldestKey = entry.getKey();
                    oldestBucket = bucket;
                }
            } finally {
                bucket.lock.unlock();
            }
        }
        if (oldestBucket == null) {
            return false;
        }
        oldestBucket.lock.lock();
        try {
            // Re-validate under the bucket lock: an admission may have landed after the sample.
            if (oldestBucket.lastEventMillis > expiredThreshold) {
                return false;
            }
            return buckets.remove(oldestKey, oldestBucket);
        } finally {
            oldestBucket.lock.unlock();
        }
    }

    private static void purgeExpired(Bucket bucket, long now, long windowMs) {
        long boundary = now - windowMs;
        int capacity = bucket.timestamps.length;
        while (bucket.size > 0 && bucket.timestamps[bucket.start] <= boundary) {
            bucket.start = (bucket.start + 1) % capacity;
            bucket.size--;
        }
    }

    private static void record(Bucket bucket, long timestamp) {
        int capacity = bucket.timestamps.length;
        bucket.timestamps[(bucket.start + bucket.size) % capacity] = timestamp;
        bucket.size++;
    }

    private static String normaliseKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            return UNKNOWN_CLIENT;
        }
        return clientKey;
    }

    private static long ceilDiv(long dividend, long divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }

    /** Per-client ring buffer of in-window admission timestamps. */
    private static final class Bucket {

        private final ReentrantLock lock = new ReentrantLock();
        private final long[] timestamps;
        private int start;
        private int size;
        private volatile long lastEventMillis;

        private Bucket(int capacity) {
            this.timestamps = new long[capacity];
        }
    }
}
