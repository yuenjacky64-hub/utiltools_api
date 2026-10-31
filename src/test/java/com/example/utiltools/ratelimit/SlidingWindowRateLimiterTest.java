package com.example.utiltools.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
}
