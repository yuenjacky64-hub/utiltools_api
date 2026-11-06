package com.example.utiltools.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlidingWindowRateLimiter")
class SlidingWindowRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock = new MutableClock(T0);

    private SlidingWindowRateLimiter limiter(int requestsPerWindow, long windowMs, int maxTrackedClients) {
        return new SlidingWindowRateLimiter(
                new RateLimitProperties(true, requestsPerWindow, windowMs, 30_000L, maxTrackedClients, false),
                clock);
    }

    @Test
    @DisplayName("admission stops exactly at the configured limit")
    void admissionStopsExactlyAtLimit() {
        SlidingWindowRateLimiter limiter = limiter(3, 1000L, 100);

        assertThat(limiter.tryAcquire("client").remaining()).isEqualTo(2L);
        assertThat(limiter.tryAcquire("client").remaining()).isEqualTo(1L);
        assertThat(limiter.tryAcquire("client").remaining()).isZero();

        SlidingWindowRateLimiter.Admission denied = limiter.tryAcquire("client");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.limit()).isEqualTo(3L);
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("concurrent admission never exceeds the limit and remaining never goes negative")
    void concurrentAdmissionNeverExceedsLimit() throws Exception {
        int limit = 10;
        int threads = 64;
        SlidingWindowRateLimiter limiter = limiter(limit, 60_000L, 100);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger negativeRemaining = new AtomicInteger();
        List<SlidingWindowRateLimiter.Admission> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SlidingWindowRateLimiter.Admission admission = limiter.tryAcquire("racer");
                    if (admission.allowed()) {
                        allowed.incrementAndGet();
                    }
                    if (admission.remaining() < 0) {
                        negativeRemaining.incrementAndGet();
                    }
                    synchronized (results) {
                        results.add(admission);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(allowed.get()).isEqualTo(limit);
        assertThat(results).hasSize(threads);
        assertThat(results).allSatisfy(a -> assertThat(a.remaining()).isGreaterThanOrEqualTo(0L));
        assertThat(negativeRemaining.get()).isZero();
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    @DisplayName("a denied request consumes no capacity")
    void deniedRequestConsumesNoCapacity() {
        SlidingWindowRateLimiter limiter = limiter(2, 1000L, 100);

        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();

        clock.advance(Duration.ofMillis(1001));
        SlidingWindowRateLimiter.Admission afterWindow = limiter.tryAcquire("client");
        assertThat(afterWindow.allowed()).isTrue();
        assertThat(afterWindow.remaining()).isEqualTo(1L);
    }

    @Test
    @DisplayName("window rollover after clock advance restores the full budget")
    void rolloverAfterClockAdvanceRestoresFullBudget() {
        SlidingWindowRateLimiter limiter = limiter(2, 1000L, 100);

        assertThat(limiter.tryAcquire("client").remaining()).isEqualTo(1L);
        assertThat(limiter.tryAcquire("client").remaining()).isZero();

        clock.advance(Duration.ofMillis(999));
        SlidingWindowRateLimiter.Admission denied = limiter.tryAcquire("client");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);

        clock.advance(Duration.ofMillis(2));
        SlidingWindowRateLimiter.Admission next = limiter.tryAcquire("client");
        assertThat(next.allowed()).isTrue();
        assertThat(next.remaining()).isEqualTo(1L);
    }

    @Test
    @DisplayName("clients are isolated from each other")
    void perClientIsolation() {
        SlidingWindowRateLimiter limiter = limiter(2, 1000L, 100);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        SlidingWindowRateLimiter.Admission other = limiter.tryAcquire("b");
        assertThat(other.allowed()).isTrue();
        assertThat(other.remaining()).isEqualTo(1L);
        assertThat(limiter.trackedClients()).isEqualTo(2);
    }

    @Test
    @DisplayName("idle eviction removes idle keys and preserves active counters")
    void idleEvictionPreservesActiveCounters() {
        SlidingWindowRateLimiter limiter = limiter(1, 1000L, 100);

        assertThat(limiter.tryAcquire("idle").allowed()).isTrue();
        assertThat(limiter.tryAcquire("active").allowed()).isTrue();
        assertThat(limiter.trackedClients()).isEqualTo(2);

        clock.advance(Duration.ofMillis(1500));
        assertThat(limiter.tryAcquire("active").allowed()).isTrue();
        assertThat(limiter.tryAcquire("active").remaining()).isZero();

        clock.advance(Duration.ofMillis(501));
        assertThat(limiter.evictIdleBuckets()).isEqualTo(1);
        assertThat(limiter.trackedClients()).isEqualTo(1);

        SlidingWindowRateLimiter.Admission stillLimited = limiter.tryAcquire("active");
        assertThat(stillLimited.allowed()).isFalse();
        assertThat(stillLimited.remaining()).isZero();
        assertThat(stillLimited.retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    @DisplayName("eviction never removes a bucket that still holds in-window events")
    void evictionNeverRemovesBucketWithInWindowEvents() {
        SlidingWindowRateLimiter limiter = limiter(2, 1000L, 100);

        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        clock.advance(Duration.ofMillis(999));

        assertThat(limiter.evictIdleBuckets()).isZero();
        assertThat(limiter.evictOldestExpiredBucket(clock.millis())).isFalse();
        assertThat(limiter.trackedClients()).isEqualTo(1);

        SlidingWindowRateLimiter.Admission second = limiter.tryAcquire("k");
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isZero();
    }

    @Test
    @DisplayName("max-tracked-clients is a hard limit under spoofed keys")
    void capIsHardLimitUnderSpoofedKeys() {
        int cap = 5;
        SlidingWindowRateLimiter limiter = limiter(3, 60_000L, cap);

        long previousRemaining = Long.MAX_VALUE;
        for (int i = 0; i < 1_000; i++) {
            SlidingWindowRateLimiter.Admission admission = limiter.tryAcquire("spoof-" + i);
            assertThat(limiter.trackedClients()).isLessThanOrEqualTo(cap);
            assertThat(admission.remaining()).isGreaterThanOrEqualTo(0L);
        }
        assertThat(limiter.trackedClients()).isEqualTo(cap);

        SlidingWindowRateLimiter.Admission firstKey = limiter.tryAcquire("spoof-0");
        assertThat(firstKey.remaining()).isLessThanOrEqualTo(previousRemaining);
        previousRemaining = firstKey.remaining();
        SlidingWindowRateLimiter.Admission again = limiter.tryAcquire("spoof-0");
        assertThat(again.remaining()).isLessThanOrEqualTo(previousRemaining);
        assertThat(limiter.trackedClients()).isEqualTo(cap);
    }

    @Test
    @DisplayName("an unseen key is rejected without insertion when every bucket is active")
    void capRejectsUnseenKeyWhenAllBucketsActiveWithoutInserting() {
        SlidingWindowRateLimiter limiter = limiter(5, 60_000L, 2);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();

        SlidingWindowRateLimiter.Admission rejected = limiter.tryAcquire("c");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(limiter.trackedClients()).isEqualTo(2);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    @DisplayName("the cap evicts the oldest idle bucket first")
    void capEvictsOldestIdleBucketFirst() {
        SlidingWindowRateLimiter limiter = limiter(2, 1000L, 2);

        assertThat(limiter.tryAcquire("idle").allowed()).isTrue();
        clock.advance(Duration.ofMillis(1001));
        assertThat(limiter.tryAcquire("active").allowed()).isTrue();
        assertThat(limiter.trackedClients()).isEqualTo(2);

        assertThat(limiter.tryAcquire("fresh").allowed()).isTrue();
        assertThat(limiter.trackedClients()).isEqualTo(2);

        // "idle" was the evicted bucket: both remaining buckets are active, so re-inserting
        // "idle" is refused by the cap instead of resurrecting a stale counter.
        SlidingWindowRateLimiter.Admission revivedIdle = limiter.tryAcquire("idle");
        assertThat(revivedIdle.allowed()).isFalse();
        assertThat(limiter.trackedClients()).isEqualTo(2);
    }

    @Test
    @DisplayName("disabled limiter admits everything and tracks nothing")
    void disabledLimiterAdmitsEverything() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
                new RateLimitProperties(false, 1, 1000L, 30_000L, 10, false), clock);

        for (int i = 0; i < 50; i++) {
            SlidingWindowRateLimiter.Admission admission = limiter.tryAcquire("client");
            assertThat(admission.allowed()).isTrue();
            assertThat(admission.remaining()).isEqualTo(1L);
        }
        assertThat(limiter.trackedClients()).isZero();
        assertThat(limiter.isEnabled()).isFalse();
        assertThat(limiter.properties().requestsPerWindow()).isEqualTo(1);
    }

    @Test
    @DisplayName("cleanup racing an admission never drops an active bucket's counter")
    void cleanupRacingAdmissionNeverDropsActiveCounter() throws Exception {
        int limit = 50;
        SlidingWindowRateLimiter limiter = limiter(limit, 100L, 100);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            for (int round = 0; round < 200; round++) {
                // The bucket is idle for >= 2 windows, so a cleanup pass may legitimately remove it
                // -- but never after an admission already recorded an in-window event.
                clock.advance(Duration.ofMillis(250));
                CountDownLatch start = new CountDownLatch(1);

                Future<Integer> admitted = pool.submit(() -> {
                    start.await();
                    int allowed = 0;
                    for (int i = 0; i < limit + 5; i++) {
                        SlidingWindowRateLimiter.Admission admission = limiter.tryAcquire("victim");
                        assertThat(admission.remaining()).isGreaterThanOrEqualTo(0L);
                        if (admission.allowed()) {
                            allowed++;
                        }
                    }
                    return allowed;
                });
                Future<?> cleanup = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 32; i++) {
                        assertThat(limiter.evictIdleBuckets()).isGreaterThanOrEqualTo(0);
                    }
                    return null;
                });

                start.countDown();
                assertThat(admitted.get(30, TimeUnit.SECONDS)).isEqualTo(limit);
                cleanup.get(30, TimeUnit.SECONDS);
                // Either the idle bucket was removed before the burst (fresh budget) or it was kept
                // with its recorded events: in both cases exactly `limit` admissions, never more.
                assertThat(limiter.trackedClients()).isEqualTo(1);
                assertThat(limiter.tryAcquire("victim").allowed()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("cap eviction racing admissions keeps the map bounded and counters truthful")
    void capEvictionRacingAdmissionsKeepsCountersTruthful() throws Exception {
        int cap = 4;
        int limit = 20;
        int threads = 8;
        SlidingWindowRateLimiter limiter = limiter(limit, 60_000L, cap);
        assertThat(limiter.tryAcquire("victim").allowed()).isTrue();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger overCap = new AtomicInteger();
        AtomicInteger negativeRemaining = new AtomicInteger();
        AtomicInteger victimAdmitted = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 400; i++) {
                        String key = id == 0 ? "victim" : "spoof-" + id + "-" + i;
                        SlidingWindowRateLimiter.Admission admission = limiter.tryAcquire(key);
                        if (limiter.trackedClients() > cap) {
                            overCap.incrementAndGet();
                        }
                        if (admission.remaining() < 0) {
                            negativeRemaining.incrementAndGet();
                        }
                        if (id == 0 && admission.allowed()) {
                            victimAdmitted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(overCap.get()).isZero();
        assertThat(negativeRemaining.get()).isZero();
        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(cap);
        // The active client keeps its true in-window count (one pre-admission + limit - 1).
        assertThat(victimAdmitted.get()).isEqualTo(limit - 1);
    }

    @Test
    @DisplayName("peek on a disabled limiter reports the full budget with a live reset instant")
    void peekReportsFallbackStateWhenDisabled() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
                new RateLimitProperties(false, 7, 1000L, 30_000L, 10, false), clock);

        SlidingWindowRateLimiter.Admission peeked = limiter.peek("client");

        assertThat(peeked.allowed()).isTrue();
        assertThat(peeked.limit()).isEqualTo(7L);
        assertThat(peeked.remaining()).isEqualTo(7L);
        assertThat(peeked.retryAfterSeconds()).isZero();
        // tryAcquire's disabled short-circuit returns resetEpochSeconds == 0; peek must not.
        long expectedReset = ceilSeconds(clock.millis() + 1000L);
        assertThat(peeked.resetEpochSeconds()).isGreaterThanOrEqualTo(expectedReset);
        assertThat(peeked.resetEpochSeconds()).isLessThan(expectedReset + 1000L);
        assertThat(limiter.trackedClients()).isZero();
    }

    @Test
    @DisplayName("peek neither consumes budget nor creates buckets")
    void peekNeverConsumesBudgetOrCreatesBuckets() {
        SlidingWindowRateLimiter limiter = limiter(3, 60_000L, 100);

        for (int i = 0; i < 50; i++) {
            SlidingWindowRateLimiter.Admission peeked = limiter.peek("ghost");
            assertThat(peeked.remaining()).isEqualTo(3L);
            assertThat(peeked.allowed()).isTrue();
        }
        assertThat(limiter.trackedClients()).isZero();

        int admitted = 0;
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("client").allowed()).isTrue();
            admitted++;
            for (int p = 0; p < 5; p++) {
                SlidingWindowRateLimiter.Admission peeked = limiter.peek("client");
                assertThat(peeked.limit()).isEqualTo(3L);
                assertThat(peeked.remaining()).isEqualTo(3L - admitted);
                assertThat(peeked.remaining()).isBetween(0L, 3L);
                assertThat(peeked.allowed()).isEqualTo(peeked.remaining() > 0L);
                assertThat(peeked.resetEpochSeconds())
                        .isGreaterThanOrEqualTo(clock.instant().getEpochSecond());
            }
        }
        assertThat(limiter.trackedClients()).isEqualTo(1);

        SlidingWindowRateLimiter.Admission exhausted = limiter.peek("client");
        assertThat(exhausted.allowed()).isFalse();
        assertThat(exhausted.remaining()).isZero();

        // A read must not shorten the budget either: the filter would still reject this client.
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();

        clock.advance(Duration.ofMillis(60_000L));
        assertThat(limiter.peek("client").remaining()).isEqualTo(3L);
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    private static long ceilSeconds(long millis) {
        return -Math.floorDiv(-millis, 1000L);
    }
}
