package com.example.utiltools.resilience;

import com.example.utiltools.audit.dto.JournalVerifyResponse;
import com.example.utiltools.audit.dto.JournalVerifyResult;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.audit.service.AuditedOperations;
import com.example.utiltools.common.GlobalExceptionHandler;
import com.example.utiltools.common.ReplayConflictException;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.datetime.service.DateTimeService;
import com.example.utiltools.json.controller.JsonController;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.dto.JsonQueryResponse;
import com.example.utiltools.json.service.JsonQueryFallbackCache;
import com.example.utiltools.json.service.JsonService;
import com.example.utiltools.text.service.TextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The last-known-good fallback contract over the real HTTP stack (standalone MockMvc, no Spring
 * context), so the degraded envelope is asserted exactly as a caller sees it:
 *
 * <ul>
 *   <li>an opt-in request during an open circuit is answered from the cache, marked stale, and the
 *       breaker is left byte-for-byte identical;</li>
 *   <li>a miss, an expired entry, a different {@code jsonPath} and a non-opt-in request all fail
 *       closed with the unchanged {@code 503 CIRCUIT_OPEN} envelope;</li>
 *   <li>an opt-in call is journalled as the non-deterministic {@code JSON_QUERY_FALLBACK} and can
 *       never be replayed.</li>
 * </ul>
 */
class JsonQueryStaleFallbackTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final long CACHE_AGE_MILLIS = 1_500L;

    private static final String DOCUMENT = "{\"a\": 1}";
    private static final String PATH = "$.a";

    private static final String OPT_IN_QUERY = """
            {
                "json": "{\\"a\\": 1}",
                "jsonPath": "$.a",
                "allowStaleFallback": true
            }
            """;

    private static final String LIVE_QUERY = """
            {
                "json": "{\\"a\\": 1}",
                "jsonPath": "$.a"
            }
            """;

    private static final String OTHER_PATH_QUERY = """
            {
                "json": "{\\"a\\": 1}",
                "jsonPath": "$.b",
                "allowStaleFallback": true
            }
            """;

    private static final String UNWARMED_QUERY = """
            {
                "json": "{\\"a\\": 1}",
                "jsonPath": "$.missing",
                "allowStaleFallback": true
            }
            """;

    private static final String BROKEN_QUERY = """
            {
                "json": "{\\"a\\": 1}",
                "jsonPath": "invalid$$path["
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MutableClock clock;
    private CircuitBreaker breaker;
    private JsonQueryFallbackCache cache;
    private JsonService jsonService;
    private AuditJournalService journal;
    private AuditedOperations auditedOperations;
    private AuditReplayService replayService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        // A one-hour cooldown keeps the breaker OPEN across the TTL advances below, so no probe is
        // ever admitted while the fallback cases are being exercised.
        breaker = new CircuitBreaker("json-query",
                new CircuitBreakerConfig(1, 1, Duration.ofHours(1)), new AuditTrail(16, clock), clock);
        cache = new JsonQueryFallbackCache(TTL, 8, clock, objectMapper);
        jsonService = new JsonService(objectMapper, breaker, cache);
        journal = new AuditJournalService(objectMapper, 200, 65536, 300L);
        auditedOperations = new AuditedOperations(new DateTimeService(), new CryptoService(), jsonService,
                new TextService(), journal);
        replayService = new AuditReplayService(journal, auditedOperations, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new JsonController(auditedOperations, jsonService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("shouldServeStaleFallbackWithoutMutatingBreaker")
    void shouldServeStaleFallbackWithoutMutatingBreaker() throws Exception {
        // A successful live call warms the cache and leaves no fallback metadata behind.
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPT_IN_QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value(1));
        Instant cachedAt = clock.instant();
        assertThat(cache.stats().size()).isEqualTo(1);

        tripBreaker();
        CircuitState stateBefore = breaker.state();
        int failuresBefore = breaker.consecutiveFailures();
        int successesBefore = breaker.consecutiveSuccesses();
        Instant resetBefore = breaker.resetAt();
        List<com.example.utiltools.resilience.AuditEntry> auditBefore = breaker.auditTrail().recent(16);
        assertThat(stateBefore).isEqualTo(CircuitState.OPEN);

        clock.advance(Duration.ofMillis(CACHE_AGE_MILLIS));

        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPT_IN_QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jsonPath").value(PATH))
                .andExpect(jsonPath("$.data.result").value(1))
                .andExpect(jsonPath("$.data.staleFallback").value(true))
                .andExpect(jsonPath("$.data.cachedAt").exists())
                .andExpect(jsonPath("$.data.cacheAgeMillis").value(1_500));

        // The degraded answer is a pure read of a side structure: the breaker is untouched.
        assertThat(breaker.state()).isEqualTo(stateBefore);
        assertThat(breaker.consecutiveFailures()).isEqualTo(failuresBefore);
        assertThat(breaker.consecutiveSuccesses()).isEqualTo(successesBefore);
        assertThat(breaker.resetAt()).isEqualTo(resetBefore);
        assertThat(breaker.auditTrail().recent(16)).isEqualTo(auditBefore);
        assertThat(breaker.probeInFlight()).isFalse();

        JsonQueryFallbackCache.Stats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(1L);
        assertThat(stats.misses()).isZero();
        assertThat(stats.size()).isEqualTo(1);

        // cachedAt is the moment the live result was stored, and it is never rewritten by the fallback.
        JsonQueryResponse served = jsonService.query(new JsonQueryRequest(DOCUMENT, PATH, true));
        assertThat(served.staleFallback()).isTrue();
        assertThat(served.cachedAt()).isEqualTo(cachedAt);
        assertThat(served.cacheAgeMillis()).isEqualTo(CACHE_AGE_MILLIS);
        assertThat(cache.stats().hits()).isEqualTo(2L);
    }

    @Test
    @DisplayName("shouldFailClosedOnMissExpiredKeyOrNoOptIn")
    void shouldFailClosedOnMissExpiredKeyOrNoOptIn() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LIVE_QUERY))
                .andExpect(status().isOk());
        assertThat(cache.stats().size()).isEqualTo(1);

        tripBreaker();

        // 1. Warm key, different jsonPath -> a different key -> miss.
        assertCircuitOpen(OTHER_PATH_QUERY);
        assertThat(cache.stats().misses()).isEqualTo(1L);
        assertThat(cache.stats().size()).isEqualTo(1);

        // 2. Warm key, no opt-in -> no cache read at all, so no counter moves.
        assertCircuitOpen(LIVE_QUERY);
        assertThat(cache.stats().misses()).isEqualTo(1L);
        assertThat(cache.stats().hits()).isZero();
        assertThat(cache.stats().size()).isEqualTo(1);

        // 3. Opt-in on a key that was never warmed -> miss.
        assertCircuitOpen(UNWARMED_QUERY);
        assertThat(cache.stats().misses()).isEqualTo(2L);
        assertThat(cache.stats().size()).isEqualTo(1);

        // 4. Expired TTL -> the entry is dropped and counted as expired, not as a hit.
        clock.advance(TTL.plusSeconds(1));
        assertCircuitOpen(OPT_IN_QUERY);
        assertThat(cache.stats().expired()).isEqualTo(1L);
        assertThat(cache.stats().hits()).isZero();
        assertThat(cache.stats().size()).isZero();

        // 5. The same key again with an empty cache -> plain miss, still CIRCUIT_OPEN.
        assertCircuitOpen(OPT_IN_QUERY);
        assertThat(cache.stats().misses()).isEqualTo(3L);
        assertThat(cache.stats().size()).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("shouldJournalStaleFallbackAsNonDeterministicAndRefuseReplay")
    void shouldJournalStaleFallbackAsNonDeterministicAndRefuseReplay() throws Exception {
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPT_IN_QUERY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LIVE_QUERY))
                .andExpect(status().isOk());

        List<AuditEntry> succeeded = journal.list(10, JournalState.SUCCEEDED);
        AuditEntry fallbackEntry = succeeded.stream()
                .filter(entry -> entry.operation() == OperationType.JSON_QUERY_FALLBACK)
                .findFirst()
                .orElseThrow();
        AuditEntry liveEntry = succeeded.stream()
                .filter(entry -> entry.operation() == OperationType.JSON_QUERY)
                .findFirst()
                .orElseThrow();

        assertThat(fallbackEntry.operation().isDeterministic()).isFalse();
        assertThat(liveEntry.operation().isDeterministic()).isTrue();

        // The replay service skips it with NON_DETERMINISTIC ...
        JournalVerifyResponse sweep = replayService.verify(10);
        JournalVerifyResult row = sweep.results().stream()
                .filter(result -> fallbackEntry.id().equals(result.entryId()))
                .findFirst()
                .orElseThrow();
        assertThat(row.skipReason()).isEqualTo("NON_DETERMINISTIC");
        assertThat(row.operation()).isEqualTo(OperationType.JSON_QUERY_FALLBACK);

        // ... the single-entry replay refuses it ...
        assertThatThrownBy(() -> replayService.replay(fallbackEntry.id()))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("non-deterministic");

        // ... the journal claim refuses it ...
        assertThatThrownBy(() -> journal.claimReplay(fallbackEntry.id()))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("non-deterministic");

        // ... and the replay dispatcher has no case for it at all.
        assertThatThrownBy(() -> auditedOperations.replayInvoke(OperationType.JSON_QUERY_FALLBACK,
                new JsonQueryRequest(DOCUMENT, PATH, true)))
                .isInstanceOf(ReplayConflictException.class)
                .hasMessageContaining("non-replayable");

        // A replay of a stored opt-in JSON_QUERY request is stripped of the flag, so it can never
        // serve stale data or emit fallback metadata.
        JsonQueryResponse replayed = (JsonQueryResponse) auditedOperations.replayInvoke(
                OperationType.JSON_QUERY, new JsonQueryRequest(DOCUMENT, PATH, true));
        assertThat(replayed.staleFallback()).isNull();
        assertThat(replayed.cachedAt()).isNull();
        assertThat(replayed.result()).isEqualTo(1);
    }

    private void assertCircuitOpen(String body) throws Exception {
        int sizeBefore = cache.stats().size();
        mockMvc.perform(post("/api/v1/utils/json/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.message").value("Service unavailable: circuit breaker is open"))
                .andExpect(jsonPath("$.details.state").value("OPEN"))
                .andExpect(jsonPath("$.details.resetAt").exists())
                .andExpect(jsonPath("$.details.reason").exists());
        // No fallback metadata and no cache entry is ever created by the failed path.
        assertThat(cache.stats().size()).isLessThanOrEqualTo(sizeBefore);
    }

    private void tripBreaker() {
        for (int i = 0; i < 10 && breaker.state() != CircuitState.OPEN; i++) {
            try {
                breaker.execute(() -> {
                    throw new IllegalStateException("forced failure");
                });
            } catch (RuntimeException expected) {
                // expected: the delegate's own exception is rethrown (or the breaker fast-fails)
            }
        }
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
