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
 * <p>Admission is weighted: each admitted request charges at least one unit (see
 * {@link RouteCostResolver}) and the bucket keeps that cost beside its timestamp together with a
 * running weighted sum (invariant IW-1). A request is admitted only while
 * {@code weightedSum + cost <= requests-per-window} (IW-2), evaluated and recorded in the same
 * bucket-lock critical section, so the ring never needs more than {@code requests-per-window} slots
 * (IW-4) and concurrent requests cannot oversubscribe the budget. A cost larger than the whole
 * budget is refused before any bucket is created (IW-5).</p>
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
     * @param limit             configured weighted budget for the window
     * @param remaining         weighted units still available in the window ({@code >= 0})
     * @param retryAfterSeconds seconds to wait before enough weighted charge expires for the caller's
     *                          next request to fit ({@code 0} when allowed, {@code >= 1} when denied)
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
     * Attempts to admit one request for {@code clientKey} at the unit cost of one.
     *
     * @param clientKey client identity; {@code null}/blank is normalised to {@code "unknown"}
     * @return the admission outcome, never {@code null}
     */
    public Admission tryAcquire(String clientKey) {
        return tryAcquire(clientKey, 1);
    }

    /**
     * Attempts to admit one request for {@code clientKey} charged {@code cost} units. The only
     * write path of the limiter.
     *
     * <p>A request is admitted only while the weighted sum of the costs still inside the trailing
     * window plus {@code cost} stays within the budget. A denial records nothing, so the caller can
     * retry once {@link Admission#retryAfterSeconds()} elapses.</p>
     *
     * @param clientKey client identity; {@code null}/blank is normalised to {@code "unknown"}
     * @param cost      weighted units to charge, must be {@code >= 1}
     * @return the admission outcome, never {@code null}
     * @throws IllegalArgumentException when {@code cost < 1}
     */
    public Admission tryAcquire(String clientKey, int cost) {
        if (cost < 1) {
            throw new IllegalArgumentException("cost must be >= 1, was " + cost);
        }
        long limit = properties.requestsPerWindow();
        long windowMs = properties.windowMs();
        if (!properties.enabled()) {
            return new Admission(true, limit, limit, 0L, 0L);
        }
        if (cost > limit) {
            // IW-5: refuse before registerBucket, so a request that can never be admitted never
            // occupies a max-tracked-clients slot.
            long now = clock.millis();
            return new Admission(false, limit, 0L,
                    Math.max(1L, ceilDiv(windowMs, 1000L)),
                    ceilDiv(now + windowMs, 1000L));
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

                if (bucket.weightedSum + cost > limit) {
                    long resetAt = earliestFreeingMillis(bucket, cost, limit, windowMs);
                    return new Admission(false, limit, 0L,
                            Math.max(1L, ceilDiv(resetAt - effectiveNow, 1000L)),
                            ceilDiv(resetAt, 1000L));
                }

                record(bucket, effectiveNow, cost);
                bucket.lastEventMillis = effectiveNow;
                long remaining = Math.max(0L, limit - bucket.weightedSum);
                long resetAt = bucket.timestamps[bucket.start] + windowMs;
                return new Admission(true, limit, remaining, 0L, ceilDiv(resetAt, 1000L));
            } finally {
                bucket.lock.unlock();
            }
        }

        // Unreachable for any real clock: repeated eviction of a bucket that had no in-window event
        // loses no admission, so the fail-open branch keeps a live client from being penalised.
        return new Admission(true, limit, Math.max(0L, limit - cost), 0L, ceilDiv(now + windowMs, 1000L));
    }

    /**
     * Reads the current window state for {@code clientKey} without consuming budget. The only
     * read path of the limiter.
     *
     * <p>The read is side-effect free: it never records an admission, never inserts a bucket,
     * never touches {@code lastEventMillis}, and never purges the ring buffer, so it can neither
     * shrink a client's budget, consume the {@code max-tracked-clients} allowance, nor keep an
     * idle bucket alive against the eviction passes. It takes only the per-bucket lock, so the
     * documented {@code mapLock} → bucket-lock order is untouched.</p>
     *
     * <p>The reported {@code remaining} is weighted (the sum of the in-window costs, not their
     * count) and is derived from the same suffix the admission path purges, never from
     * {@code weightedSum}, which is only authoritative right after a purge.</p>
     *
     * @param clientKey client identity; {@code null}/blank is normalised to {@code "unknown"}
     * @return the current state, never {@code null}; {@code allowed} is {@code true} exactly when
     *         {@code remaining > 0}
     */
    public Admission peek(String clientKey) {
        long limit = properties.requestsPerWindow();
        long windowMs = properties.windowMs();
        long now = clock.millis();
        if (!properties.enabled()) {
            // Unlike tryAcquire's disabled short-circuit (resetEpochSeconds == 0), a status reader
            // must report a live reset instant: REQ-03 asks for now + windowMs.
            return new Admission(true, limit, limit, 0L, ceilDiv(now + windowMs, 1000L));
        }

        String key = normaliseKey(clientKey);

        for (int attempt = 0; attempt < MAX_MAPPING_ATTEMPTS; attempt++) {
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                // Deliberately no registerBucket(): a read may not insert, cap-evict, or fail.
                return new Admission(true, limit, limit, 0L, ceilDiv(now + windowMs, 1000L));
            }

            bucket.lock.lock();
            try {
                // I8: never read an instance an eviction path already removed from the map.
                if (buckets.get(key) != bucket) {
                    continue;
                }

                // Clock-regression guard: the window never moves backwards.
                long effectiveNow = Math.max(now, bucket.lastEventMillis);
                long boundary = effectiveNow - windowMs;
                int capacity = bucket.timestamps.length;
                int inWindow = 0;
                long oldestInWindow = 0L;
                long weightedInWindow = 0L;
                // Entries are recorded in non-decreasing time order, so the in-window set is a
                // suffix: walk newest first and stop at the first expired entry.
                for (int i = 0; i < bucket.size; i++) {
                    int slot = Math.floorMod(bucket.start + bucket.size - 1 - i, capacity);
                    long timestamp = bucket.timestamps[slot];
                    if (timestamp <= boundary) {
                        break;
                    }
                    inWindow++;
                    weightedInWindow += bucket.costs[slot];
                    oldestInWindow = timestamp;
                }

                if (weightedInWindow >= limit) {
                    long resetAtMillis = oldestInWindow + windowMs;
                    return new Admission(false, limit, 0L,
                            Math.max(1L, ceilDiv(resetAtMillis - effectiveNow, 1000L)),
                            ceilDiv(resetAtMillis, 1000L));
                }
                long remaining = Math.max(0L, limit - weightedInWindow);
                long resetAtMillis = inWindow == 0 ? effectiveNow + windowMs : oldestInWindow + windowMs;
                return new Admission(true, limit, remaining, 0L, ceilDiv(resetAtMillis, 1000L));
            } finally {
                bucket.lock.unlock();
            }
        }

        // Unreachable for any real clock: fail open rather than report a state we could not read.
        return new Admission(true, limit, limit, 0L, ceilDiv(now + windowMs, 1000L));
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
                    // so no admission can record an in-window event in between. Purging first makes
                    // weightedSum authoritative (IW-1), so a bucket that still holds weighted charge
                    // is never evicted.
                    purgeExpired(bucket, Math.max(now, bucket.lastEventMillis), properties.windowMs());
                    if (bucket.lastEventMillis <= idleThreshold
                            && bucket.weightedSum == 0L
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
        long windowMs = properties.windowMs();
        long expiredThreshold = now - windowMs;
        String oldestKey = null;
        Bucket oldestBucket = null;
        long oldestLastEvent = Long.MAX_VALUE;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            bucket.lock.lock();
            try {
                purgeExpired(bucket, Math.max(now, bucket.lastEventMillis), windowMs);
                long lastEvent = bucket.lastEventMillis;
                if (lastEvent <= expiredThreshold
                        && bucket.weightedSum == 0L
                        && lastEvent < oldestLastEvent) {
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
            purgeExpired(oldestBucket, Math.max(now, oldestBucket.lastEventMillis), windowMs);
            if (oldestBucket.lastEventMillis > expiredThreshold || oldestBucket.weightedSum != 0L) {
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
            // IW-1: subtract exactly the cost of the entry that leaves the window.
            bucket.weightedSum -= bucket.costs[bucket.start];
            bucket.start = (bucket.start + 1) % capacity;
            bucket.size--;
        }
    }

    private static void record(Bucket bucket, long timestamp, int cost) {
        int capacity = bucket.timestamps.length;
        int slot = (bucket.start + bucket.size) % capacity;
        bucket.timestamps[slot] = timestamp;
        bucket.costs[slot] = cost;
        bucket.size++;
        bucket.weightedSum += cost;
    }

    /**
     * Earliest instant at which an admission of {@code cost} units fits the budget again: walks the
     * ring FIFO and accumulates the costs of the entries that will expire. Bounded by the ring size
     * and used only on the deny path. Falls back to the oldest entry's expiry when no single entry
     * frees enough, which keeps the advertised retry optimistic rather than wrong.
     */
    private static long earliestFreeingMillis(Bucket bucket, int cost, long limit, long windowMs) {
        long needed = bucket.weightedSum + cost - limit;
        long freed = 0L;
        int capacity = bucket.timestamps.length;
        for (int i = 0; i < bucket.size; i++) {
            int slot = (bucket.start + i) % capacity;
            freed += bucket.costs[slot];
            if (freed >= needed) {
                return bucket.timestamps[slot] + windowMs;
            }
        }
        return bucket.timestamps[bucket.start] + windowMs;
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

    /** Per-client ring buffer of in-window admission timestamps and the cost each one charged. */
    private static final class Bucket {

        private final ReentrantLock lock = new ReentrantLock();
        private final long[] timestamps;
        private final int[] costs;
        private int start;
        private int size;
        private long weightedSum;
        private volatile long lastEventMillis;

        private Bucket(int capacity) {
            this.timestamps = new long[capacity];
            this.costs = new int[capacity];
        }
    }
}
