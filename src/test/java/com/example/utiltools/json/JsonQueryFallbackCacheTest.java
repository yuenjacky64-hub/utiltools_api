package com.example.utiltools.json;

import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.service.JsonQueryFallbackCache;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.resilience.AuditTrail;
import com.example.utiltools.resilience.CircuitBreaker;
import com.example.utiltools.resilience.CircuitBreakerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused unit tests for {@link JsonQueryFallbackCache}: content-derived keys, the TTL bound,
 * oldest-first eviction, the bounded counters and the two safety rules — a stale response is never
 * stored, and a failed live query never disturbs the last known good value.
 */
class JsonQueryFallbackCacheTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_ENTRIES = 2;
    private static final String DOC = "{\"a\": 1}";
    private static final String PATH = "$.a";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MutableClock clock;
    private JsonQueryFallbackCache cache;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        cache = new JsonQueryFallbackCache(TTL, MAX_ENTRIES, clock, objectMapper);
    }

    @Test
    @DisplayName("keyFor - identical document modulo whitespace shares a key, a different path does not")
    void shouldKeyByNormalizedDocumentAndPath() {
        Optional<String> formatted = cache.keyFor("{ \"a\":   1 }", " $.a ");
        Optional<String> compact = cache.keyFor(DOC, PATH);

        assertThat(formatted).isPresent().isEqualTo(compact);
        assertThat(cache.keyFor(DOC, "$.b")).isNotEqualTo(compact);
        assertThat(cache.keyFor("{\"a\": 2}", PATH)).isNotEqualTo(compact);
        assertThat(cache.keyFor(" ", PATH)).isEmpty();
        assertThat(cache.keyFor(DOC, null)).isEmpty();
    }

    @Test
    @DisplayName("shouldKeepLastGoodValueAndEvictOldestFirst")
    void shouldKeepLastGoodValueAndEvictOldestFirst() {
        JsonQueryResponse first = live("$.a", 1);
        cache.remember("{\"a\": 1}", "$.a", first);
        cache.remember("{\"b\": 2}", "$.b", live("$.b", 2));

        // A failed live query never reaches remember(): the good value survives untouched.
        assertThatThrownBy(() -> jsonService().query(new JsonQueryRequest("{\"a\": 1}", "bad$$path[")))
                .isInstanceOf(IllegalArgumentException.class);
        Optional<JsonQueryResponse> survived = cache.serve("{\"a\": 1}", "$.a");
        assertThat(survived).isPresent();
        assertThat(survived.orElseThrow().result()).isEqualTo(1);
        assertThat(survived.orElseThrow().cachedAt()).isEqualTo(clock.instant());

        // Third distinct key overflows the bound of two: the oldest key is evicted first.
        cache.remember("{\"c\": 3}", "$.c", live("$.c", 3));

        JsonQueryFallbackCache.Stats stats = cache.stats();
        assertThat(stats.size()).isEqualTo(MAX_ENTRIES);
        assertThat(stats.evictions()).isEqualTo(1L);
        assertThat(stats.ttlMillis()).isEqualTo(TTL.toMillis());
        assertThat(stats.maxEntries()).isEqualTo(MAX_ENTRIES);

        assertThat(cache.serve("{\"a\": 1}", "$.a")).isEmpty();
        assertThat(cache.serve("{\"b\": 2}", "$.b")).isPresent();
        assertThat(cache.serve("{\"c\": 3}", "$.c")).isPresent();
        // The survival probe above was the first hit; the two live keys are the second and third.
        assertThat(cache.stats().misses()).isEqualTo(1L);
        assertThat(cache.stats().hits()).isEqualTo(3L);
    }

    @Test
    @DisplayName("shouldNeverStoreOrExtendAStaleFallback")
    void shouldNeverStoreOrExtendAStaleFallback() {
        cache.remember(DOC, PATH, live(PATH, 1));
        clock.advance(Duration.ofSeconds(50));

        JsonQueryResponse served = cache.serve(DOC, PATH).orElseThrow();
        assertThat(served.staleFallback()).isTrue();
        assertThat(served.cacheAgeMillis()).isEqualTo(50_000L);

        // Feeding the stale answer back in must be a no-op: no new entry, no refreshed TTL.
        cache.remember(DOC, PATH, served);
        assertThat(cache.stats().size()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(11));
        assertThat(cache.serve(DOC, PATH)).isEmpty();
        assertThat(cache.stats().expired()).isEqualTo(1L);
        assertThat(cache.stats().size()).isZero();
    }

    @Test
    @DisplayName("shouldExpireEntriesAndSweepThem")
    void shouldExpireEntriesAndSweepThem() {
        cache = new JsonQueryFallbackCache(TTL, 8, clock, objectMapper);
        cache.remember("{\"a\": 1}", "$.a", live("$.a", 1));
        cache.remember("{\"b\": 2}", "$.b", live("$.b", 2));
        clock.advance(Duration.ofSeconds(30));
        cache.remember("{\"c\": 3}", "$.c", live("$.c", 3));

        assertThat(cache.sweepExpired()).isZero();

        clock.advance(Duration.ofSeconds(31));

        // Only the two entries older than the TTL are swept; the freshness of the third is untouched.
        assertThat(cache.sweepExpired()).isEqualTo(2);
        assertThat(cache.stats()).isEqualTo(
                new JsonQueryFallbackCache.Stats(0L, 0L, 2L, 0L, 1, TTL.toMillis(), 8));
        assertThat(cache.serve("{\"c\": 3}", "$.c")).isPresent();
    }

    @Test
    @DisplayName("shouldIgnoreUncacheableAndNullValues")
    void shouldIgnoreUncacheableAndNullValues() {
        cache.remember(DOC, PATH, null);
        cache.remember(" ", PATH, live(PATH, 1));
        cache.remember(DOC, null, live(PATH, 1));

        assertThat(cache.stats().size()).isZero();
        assertThat(cache.serve(DOC, " ")).isEmpty();
    }

    private JsonService jsonService() {
        CircuitBreaker breaker = new CircuitBreaker("json-query",
                new CircuitBreakerConfig(1, 1, Duration.ofSeconds(30)), new AuditTrail(16, clock), clock);
        return new JsonService(objectMapper, breaker, cache);
    }

    private static JsonQueryResponse live(String jsonPath, Object result) {
        return new JsonQueryResponse(jsonPath, result);
    }

    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
