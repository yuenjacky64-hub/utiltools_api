package com.example.utiltools.ratelimit;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.utiltools.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private MockMvc mockMvc(RateLimitProperties properties, ProbeController probe) {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(properties, clock);
        return MockMvcBuilders.standaloneSetup(probe)
                .addFilters(new RateLimitFilter(limiter, properties, objectMapper))
                .build();
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
    @DisplayName("a limiter failure fails open instead of blocking traffic")
    void limiterFailureDoesNotBlockTraffic() throws Exception {
        ProbeController probe = new ProbeController();
        RateLimitProperties properties = properties(true, 5, false);
        SlidingWindowRateLimiter brokenLimiter = new SlidingWindowRateLimiter(properties, clock) {
            @Override
            public Admission tryAcquire(String clientKey) {
                throw new IllegalStateException("boom");
            }
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(probe)
                .addFilters(new RateLimitFilter(brokenLimiter, properties, objectMapper))
                .build();

        mockMvc.perform(get("/api/v1/utils/probe")).andExpect(status().isOk());
        assertThat(probe.invocations.get()).isEqualTo(1);
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
    }
}
