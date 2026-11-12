package com.example.utiltools.resilience;

import com.example.utiltools.json.service.JsonQueryFallbackCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires the JSONPath circuit breaker, its probe lease and the scheduled reaper that releases
 * leases left behind by a probe that blocked past {@code utiltools.resilience.probe-lease-seconds}.
 * Also wires the last-known-good fallback cache for opt-in {@code JSON_QUERY} calls and the
 * scheduled sweep that drops its expired entries.
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    // Provider, not the bean: this class declares the breaker bean, so eager field injection
    // would make config <-> breaker circular (the same pattern RateLimitConfig uses).
    @Autowired
    private ObjectProvider<CircuitBreaker> breakerProvider;

    // Same anti-circularity reason: the fallback cache bean is declared by this class.
    @Autowired
    private ObjectProvider<JsonQueryFallbackCache> fallbackCacheProvider;

    @Bean
    public CircuitBreaker jsonPathCircuitBreaker(
            @Value("${utiltools.resilience.probe-lease-seconds:10}") long probeLeaseSeconds) {
        Clock clock = Clock.systemUTC();
        CircuitBreakerConfig defaults = CircuitBreakerConfig.defaults();
        CircuitBreakerConfig config = new CircuitBreakerConfig(
                defaults.failureThreshold(),
                defaults.successThreshold(),
                defaults.cooldown(),
                Duration.ofSeconds(Math.max(1L, probeLeaseSeconds)));
        return new CircuitBreaker("json-query", config, new AuditTrail(1024, clock), clock);
    }

    @Bean
    public JsonQueryFallbackCache jsonQueryFallbackCache(
            ObjectMapper objectMapper,
            @Value("${utiltools.resilience.fallback-cache-ttl-ms:60000}") long ttlMs,
            @Value("${utiltools.resilience.fallback-cache-max-entries:256}") int maxEntries) {
        return new JsonQueryFallbackCache(Duration.ofMillis(Math.max(1L, ttlMs)),
                Math.max(1, maxEntries), Clock.systemUTC(), objectMapper);
    }

    @Scheduled(fixedDelayString = "${utiltools.resilience.probe-reaper-interval-ms:5000}")
    public void reapExpiredProbes() {
        CircuitBreaker breaker = breakerProvider.getIfAvailable();
        if (breaker != null && breaker.reapExpiredProbe()) {
            log.debug("released an expired HALF_OPEN probe lease on {}", breaker.name());
        }
    }

    @Scheduled(fixedDelayString = "${utiltools.resilience.fallback-cache-sweep-interval-ms:30000}")
    public void sweepExpiredFallbackEntries() {
        JsonQueryFallbackCache cache = fallbackCacheProvider.getIfAvailable();
        if (cache != null) {
            cache.sweepExpired();
        }
    }
}
