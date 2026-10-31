package com.example.utiltools.ratelimit;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
 */
@Component
public class SlidingWindowRateLimiter {

    /** Error code carried in the unified {@code ApiResponse} envelope for a rejection. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    private static final String UNKNOWN_CLIENT = "unknown";

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
            List<String> idleKeys = new ArrayList<>();
            for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
                Bucket bucket = entry.getValue();
                bucket.lock.lock();
                try {
                    if (bucket.lastEventMillis <= idleThreshold) {
                        idleKeys.add(entry.getKey());
                    }
                } finally {
                    bucket.lock.unlock();
                }
            }
            for (String idleKey : idleKeys) {
                if (buckets.remove(idleKey) != null) {
                    removed++;
                }
            }
        } finally {
            mapLock.unlock();
        }
        return removed;
    }

    /**
     * Removes at most one bucket whose last event already left the window: the oldest idle one.
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
                removeOldestExpiredBucketLocked(now);
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

    /** Removes the {@code mapLock}-protected oldest bucket that has no in-window event left. */
    private boolean removeOldestExpiredBucketLocked(long now) {
        long expiredThreshold = now - properties.windowMs();
        String oldestKey = null;
        long oldestLastEvent = Long.MAX_VALUE;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            long lastEvent = entry.getValue().lastEventMillis;
            if (lastEvent <= expiredThreshold && lastEvent < oldestLastEvent) {
                oldestLastEvent = lastEvent;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey == null) {
            return false;
        }
        buckets.remove(oldestKey);
        return true;
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
