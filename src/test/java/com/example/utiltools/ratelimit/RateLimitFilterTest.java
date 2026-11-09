package com.example.utiltools.ratelimit;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.common.RequestIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private RateLimitProperties properties(boolean enabled, int requestsPerWindow, boolean trustForwarded) {
        return new RateLimitProperties(enabled, requestsPerWindow, 60_000L, 30_000L, 1_000, trustForwarded);
    }

    /** The documented defaults: default 1, audit/verify 5, jobs/hash 3, json/query 2. */
    private RouteCostResolver costResolver() {
        return new RouteCostResolver(new RateLimitCostProperties(1, 5, 3, 2));
    }

    /** Client key the filter resolves for MockMvc's default remote address. */
    private static final String CLIENT_KEY = "127.0.0.1";

    private MockMvc mockMvc(RateLimitProperties properties, ProbeController probe) {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        return mockMvc(properties, limiter, costResolver(), probe);
    }

    private MockMvc mockMvc(RateLimitProperties properties, SlidingWindowRateLimiter limiter,
                            RouteCostResolver resolver, ProbeController probe) {
        return MockMvcBuilders.standaloneSetup(probe)
                .addFilters(new RateLimitFilter(limiter, properties, resolver, objectMapper))
                .build();
    }

    /**
     * Standalone slice wiring the real filter, the real request-id filter (so the envelope carries a
     * {@code requestId}) and the status controller over one shared limiter.
     */
    private MockMvc statusMockMvc(RateLimitProperties properties, SlidingWindowRateLimiter limiter) {
        return MockMvcBuilders.standaloneSetup(new RateLimitStatusController(limiter, properties, clock))
                .addFilters(new RequestIdFilter(),
                        new RateLimitFilter(limiter, properties, costResolver(), objectMapper))
                .build();
    }

    private static final String STATUS_PATH = "/api/v1/utils/rate-limit/status";

    @Test
    @DisplayName("status endpoint answers 200 with a success envelope on a fresh client")
    void statusEndpointReturnsOkEnvelopeAndHeaders() throws Exception {
        RateLimitProperties properties = properties(true, 5, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);

        statusMockMvc(properties, limiter).perform(get(STATUS_PATH))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.limit").value(5))
                .andExpect(jsonPath("$.data.remaining").value(4));
    }

    @Test
    @DisplayName("status body limits, header values and resetAt agree numerically")
    void statusEndpointBodyMatchesRateLimitHeaders() throws Exception {
        RateLimitProperties properties = properties(true, 4, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);

        MvcResult result = statusMockMvc(properties, limiter).perform(get(STATUS_PATH))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("limit").isInt()).isTrue();
        assertThat(data.get("remaining").isInt()).isTrue();
        assertThat(data.get("resetAt").isIntegralNumber()).isTrue();
        assertThat(data.get("allowed").isBoolean()).isTrue();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getHeader("X-RateLimit-Limit"))
                .isEqualTo(Integer.toString(data.get("limit").asInt()));
        assertThat(response.getHeader("X-RateLimit-Remaining"))
                .isEqualTo(Integer.toString(data.get("remaining").asInt()));
        assertThat(response.getHeader("X-RateLimit-Reset"))
                .isEqualTo(Long.toString(data.get("resetAt").asLong()));

        long resetAt = data.get("resetAt").asLong();
        assertThat(resetAt).isGreaterThanOrEqualTo(clock.millis() / 1000L);
        assertThat(resetAt).isLessThanOrEqualTo((clock.millis() + properties.windowMs()) / 1000L + 1L);
        assertThat(data.get("remaining").asInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("remaining decrements by one per status call and the next call is the 429")
    void statusEndpointRemainingTracksSuccessiveRequests() throws Exception {
        RateLimitProperties properties = properties(true, 3, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        MockMvc mvc = statusMockMvc(properties, limiter);

        for (int expectedRemaining = 2; expectedRemaining >= 0; expectedRemaining--) {
            mvc.perform(get(STATUS_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.limit").value(3))
                    .andExpect(jsonPath("$.data.remaining").value(expectedRemaining))
                    .andExpect(jsonPath("$.data.allowed").value(expectedRemaining > 0));
        }

        // Same bucket, same budget: the read endpoint proves which bucket the filter counts.
        mvc.perform(get(STATUS_PATH))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    @DisplayName("exhausted budget is reported with allowed=false but still HTTP 200")
    void statusEndpointReportsExhaustedWindowWithoutFailing() throws Exception {
        RateLimitProperties properties = properties(true, 2, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        MockMvc mvc = statusMockMvc(properties, limiter);

        mvc.perform(get(STATUS_PATH)).andExpect(status().isOk());

        MvcResult result = mvc.perform(get(STATUS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.data.remaining").value(0))
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andReturn();

        long resetAt = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("resetAt").asLong();
        assertThat(resetAt).isGreaterThanOrEqualTo(clock.millis() / 1000L);
        assertThat(resetAt).isEqualTo((clock.millis() + properties.windowMs()) / 1000L);

        mvc.perform(get(STATUS_PATH)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("disabled limiter reports the full default budget instead of rejecting")
    void statusEndpointFallsBackToDefaultsWhenDisabled() throws Exception {
        RateLimitProperties properties = properties(false, 60, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);

        MvcResult result = statusMockMvc(properties, limiter).perform(get(STATUS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.limit").value(60))
                .andExpect(jsonPath("$.data.remaining").value(60))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("60");

        long windowMs = 60_000L;
        long expectedReset = (clock.millis() + windowMs) / 1000L;
        assertThat(objectMapper.readTree(response.getContentAsString()).get("data").get("resetAt").asLong())
                .isBetween(expectedReset, expectedReset + 1L);
        assertThat(Long.parseLong(response.getHeader("X-RateLimit-Reset")))
                .isBetween(expectedReset, expectedReset + 1L);
    }

    @Test
    @DisplayName("a failing limiter read fails open with HTTP 200 and the full budget")
    void statusEndpointFailsOpenWhenLimiterThrows() throws Exception {
        RateLimitProperties properties = properties(true, 4, false);
        SlidingWindowRateLimiter throwingLimiter = new SlidingWindowRateLimiter(properties, clock) {
            @Override
            public Admission peek(String clientKey) {
                throw new IllegalStateException("bucket store unavailable");
            }
        };

        MvcResult result = statusMockMvc(properties, throwingLimiter).perform(get(STATUS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.limit").value(4))
                .andExpect(jsonPath("$.data.remaining").value(4))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo("4");
    }

    @Test
    @DisplayName("status endpoint and filter resolve the same client key in both trust modes")
    void statusEndpointUsesSameClientKeyAsFilter() throws Exception {
        // trust-forwarded-header off: a spoofed header must be ignored by both paths.
        RateLimitProperties untrusted = properties(true, 3, false);
        SlidingWindowRateLimiter untrustedLimiter = new SlidingWindowRateLimiter(untrusted, clock);
        MockMvc untrustedMvc = statusMockMvc(untrusted, untrustedLimiter);

        untrustedMvc.perform(get(STATUS_PATH).header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(2));
        untrustedMvc.perform(get(STATUS_PATH).header("X-Forwarded-For", "198.51.100.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(1));

        // trust-forwarded-header on: the first well-formed entry names the bucket for both paths.
        RateLimitProperties trusted = properties(true, 3, true);
        SlidingWindowRateLimiter trustedLimiter = new SlidingWindowRateLimiter(trusted, clock);
        MockMvc trustedMvc = statusMockMvc(trusted, trustedLimiter);

        trustedMvc.perform(get(STATUS_PATH).header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(2));
        trustedMvc.perform(get(STATUS_PATH).header("X-Forwarded-For", "198.51.100.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(2))
                .andExpect(jsonPath("$.data.allowed").value(true));
        trustedMvc.perform(get(STATUS_PATH).header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(1));
    }

    @Test
    @DisplayName("burst of limit + 1 yields limit 200s and one 429 envelope without reaching the controller")
    void burstRejectsRequestBeyondLimit() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mockMvc = mockMvc(properties(true, 3, false), probe);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/utils/probe"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(2 - i)));
        }
        assertThat(probe.invocations.get()).isEqualTo(3);

        mockMvc.perform(get("/api/v1/utils/probe"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        assertThat(probe.invocations.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("rejection uses the unified envelope and all rate-limit headers")
    void rejectionUsesUnifiedEnvelopeAndHeaders() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mockMvc = mockMvc(properties(true, 1, false), probe);

        mockMvc.perform(get("/api/v1/utils/probe")).andExpect(status().isOk());

        String retryAfter = mockMvc.perform(get("/api/v1/utils/probe"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.details.limit").value(1))
                .andExpect(jsonPath("$.details.windowMs").value(60_000))
                .andExpect(jsonPath("$.details.retryAfterSeconds")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("allowed responses expose remaining capacity without changing the body")
    void allowedResponseExposesRemainingWithoutChangingBody() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mockMvc = mockMvc(properties(true, 5, false), probe);

        mockMvc.perform(get("/api/v1/utils/probe"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "4"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Operation successful"))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    @DisplayName("X-Forwarded-For is only honoured when trust-forwarded-header is enabled")
    void forwardedHeaderHonoredOnlyWhenTrusted() throws Exception {
        ProbeController untrustedProbe = new ProbeController();
        MockMvc untrusted = mockMvc(properties(true, 1, false), untrustedProbe);

        untrusted.perform(get("/api/v1/utils/probe").header("X-Forwarded-For", "203.0.113.5"))
                .andExpect(status().isOk());
        // Same remote address, different spoofed header: still one bucket => limited.
        untrusted.perform(get("/api/v1/utils/probe").header("X-Forwarded-For", "203.0.113.6"))
                .andExpect(status().isTooManyRequests());

        ProbeController trustedProbe = new ProbeController();
        MockMvc trusted = mockMvc(properties(true, 1, true), trustedProbe);

        trusted.perform(get("/api/v1/utils/probe").header("X-Forwarded-For", "203.0.113.5"))
                .andExpect(status().isOk());
        trusted.perform(get("/api/v1/utils/probe").header("X-Forwarded-For", "203.0.113.6"))
                .andExpect(status().isOk());
        trusted.perform(get("/api/v1/utils/probe").header("X-Forwarded-For", "203.0.113.5"))
                .andExpect(status().isTooManyRequests());
        // Malformed forwarded entry falls back to the servlet remote address.
        trusted.perform(get("/api/v1/utils/probe").header("X-Forwarded-For", "not an address!"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("actuator, api-docs and swagger-ui paths bypass the limiter")
    void excludedPathsPassThrough() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mockMvc = mockMvc(properties(true, 1, false), probe);

        for (String path : new String[]{"/actuator/health", "/v3/api-docs", "/swagger-ui/index.html"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"))
                    .andExpect(header().doesNotExist("X-RateLimit-Remaining"));
        }
        assertThat(probe.invocations.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("configured routes are charged their weight while unknown routes cost one")
    void routeCostMappingChargesConfiguredWeights() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mockMvc = mockMvc(properties(true, 30, false), probe);

        mockMvc.perform(post("/api/v1/utils/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "30"))
                .andExpect(header().string("X-RateLimit-Cost", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "25"));

        mockMvc.perform(post("/api/v1/utils/jobs/hash"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Cost", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "22"));

        mockMvc.perform(post("/api/v1/utils/json/query"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Cost", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "20"));

        // A route without a configured weight costs the default of 1.
        mockMvc.perform(get("/api/v1/utils/probe"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Cost", "1"))
                .andExpect(header().string("X-RateLimit-Remaining", "19"));

        assertThat(probe.invocations.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("an expensive route denial reports the cost, Retry-After and the weighted budget")
    void expensiveRouteDenialReportsCostAndRetryAfter() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mockMvc = mockMvc(properties(true, 5, false), probe);

        mockMvc.perform(post("/api/v1/utils/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Cost", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
        assertThat(probe.invocations.get()).isEqualTo(1);

        String retryAfter = mockMvc.perform(post("/api/v1/utils/audit/verify"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("X-RateLimit-Cost", "5"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.cost").value(5))
                .andExpect(jsonPath("$.details.limit").value(5))
                .andExpect(jsonPath("$.details.windowMs").value(60_000))
                .andExpect(jsonPath("$.details.retryAfterSeconds")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1L);
        // The denial recorded nothing, so the controller was never reached a second time.
        assertThat(probe.invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a single cost above the whole budget is denied without creating a bucket")
    void costAboveBudgetIsDeniedWithoutCreatingBucket() throws Exception {
        ProbeController probe = new ProbeController();
        RateLimitProperties properties = properties(true, 3, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        MockMvc mockMvc = mockMvc(properties, limiter, costResolver(), probe);

        mockMvc.perform(post("/api/v1/utils/audit/verify"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Cost", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.details.cost").value(5))
                .andExpect(jsonPath("$.details.limit").value(3));

        assertThat(limiter.trackedClients()).isZero();
        assertThat(limiter.peek(CLIENT_KEY).remaining()).isEqualTo(3L);
        assertThat(probe.invocations.get()).isZero();
    }

    @Test
    @DisplayName("weighted headers and 429 details agree with the limiter state")
    void weightedHeadersAnd429DetailsAgreeWithLimiterState() throws Exception {
        ProbeController probe = new ProbeController();
        RateLimitProperties properties = properties(true, 7, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        MockMvc mockMvc = mockMvc(properties, limiter, costResolver(), probe);

        MockHttpServletResponse admitted = mockMvc.perform(post("/api/v1/utils/json/query"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(admitted.getHeader("X-RateLimit-Remaining"))
                .isEqualTo(Long.toString(limiter.peek(CLIENT_KEY).remaining()));
        assertThat(limiter.peek(CLIENT_KEY).remaining()).isEqualTo(5L);

        mockMvc.perform(post("/api/v1/utils/audit/verify")).andExpect(status().isOk());
        assertThat(limiter.peek(CLIENT_KEY).remaining()).isZero();

        MockHttpServletResponse denied = mockMvc.perform(post("/api/v1/utils/audit/verify"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse();
        JsonNode details = objectMapper.readTree(denied.getContentAsString()).get("details");
        SlidingWindowRateLimiter.Admission peeked = limiter.peek(CLIENT_KEY);
        assertThat(denied.getHeader("X-RateLimit-Limit"))
                .isEqualTo(Long.toString(peeked.limit()));
        assertThat(denied.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(details.get("limit").asLong()).isEqualTo(peeked.limit());
        assertThat(details.get("cost").asInt()).isEqualTo(5);
        // The denial left the window untouched.
        assertThat(peeked.remaining()).isZero();
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    @DisplayName("status endpoint reports weighted remaining without a second admission")
    void statusEndpointReportsWeightedRemainingWithoutCharging() throws Exception {
        RateLimitProperties properties = properties(true, 10, false);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        MockMvc mvc = statusMockMvc(properties, limiter);

        assertThat(limiter.tryAcquire(CLIENT_KEY, 6).remaining()).isEqualTo(4L);

        mvc.perform(get(STATUS_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "10"))
                .andExpect(header().string("X-RateLimit-Remaining", "3"))
                .andExpect(jsonPath("$.data.limit").value(10))
                .andExpect(jsonPath("$.data.remaining").value(3))
                .andExpect(jsonPath("$.data.allowed").value(true));

        // One charge per request: the peek path added nothing on top of the filter's own unit.
        assertThat(limiter.peek(CLIENT_KEY).remaining()).isEqualTo(3L);
        assertThat(limiter.trackedClients()).isEqualTo(1);

        mvc.perform(get(STATUS_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "2"))
                .andExpect(jsonPath("$.data.remaining").value(2));
    }

    @Test
    @DisplayName("a limiter or resolver failure fails open instead of blocking traffic")
    void limiterAndResolverFailuresFailOpen() throws Exception {
        RateLimitProperties properties = properties(true, 5, false);

        ProbeController limiterProbe = new ProbeController();
        SlidingWindowRateLimiter brokenLimiter = new SlidingWindowRateLimiter(properties, clock) {
            @Override
            public Admission tryAcquire(String clientKey, int cost) {
                throw new IllegalStateException("boom");
            }
        };
        MockMvc limiterMvc = mockMvc(properties, brokenLimiter, costResolver(), limiterProbe);

        limiterMvc.perform(get("/api/v1/utils/probe")).andExpect(status().isOk());
        assertThat(limiterProbe.invocations.get()).isEqualTo(1);

        ProbeController resolverProbe = new ProbeController();
        RouteCostResolver brokenResolver = new RouteCostResolver(new RateLimitCostProperties(1, 5, 3, 2)) {
            @Override
            public int resolve(String method, String uri) {
                throw new IllegalStateException("boom");
            }
        };
        SlidingWindowRateLimiter untouchedLimiter = new SlidingWindowRateLimiter(properties, clock);
        MockMvc resolverMvc = mockMvc(properties, untouchedLimiter, brokenResolver, resolverProbe);

        resolverMvc.perform(post("/api/v1/utils/audit/verify")).andExpect(status().isOk());
        assertThat(resolverProbe.invocations.get()).isEqualTo(1);
        assertThat(untouchedLimiter.trackedClients()).isZero();
    }

    @Test
    @DisplayName("enabled=false bypasses limiting entirely")
    void disabledPropertyBypassesLimiting() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mockMvc = mockMvc(properties(false, 1, false), probe);

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/utils/probe"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"))
                    .andExpect(header().doesNotExist("X-RateLimit-Remaining"));
        }
        assertThat(probe.invocations.get()).isEqualTo(100);
    }

    /** Minimal controller used to observe whether a request reached MVC dispatch. */
    @RestController
    static class ProbeController {

        private final AtomicInteger invocations = new AtomicInteger();

        @GetMapping("/api/v1/utils/probe")
        public ApiResponse<String> probe() {
            invocations.incrementAndGet();
            return ApiResponse.success("ok");
        }

        @GetMapping({"/actuator/health", "/v3/api-docs", "/swagger-ui/index.html"})
        public ApiResponse<String> excluded() {
            invocations.incrementAndGet();
            return ApiResponse.success("ok");
        }

        @PostMapping({"/api/v1/utils/audit/verify", "/api/v1/utils/jobs/hash", "/api/v1/utils/json/query"})
        public ApiResponse<String> costed() {
            invocations.incrementAndGet();
            return ApiResponse.success("ok");
        }
    }
}
