package com.example.utiltools.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires the JSONPath circuit breaker, its probe lease and the scheduled reaper that releases
 * leases left behind by a probe that blocked past {@code utiltools.resilience.probe-lease-seconds}.
 */
@Configuration
@EnableScheduling
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    // Provider, not the bean: this class declares the breaker bean, so eager field injection
    // would make config <-> breaker circular (the same pattern RateLimitConfig uses).
    @Autowired
    private ObjectProvider<CircuitBreaker> breakerProvider;

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

    @Scheduled(fixedDelayString = "${utiltools.resilience.probe-reaper-interval-ms:5000}")
    public void reapExpiredProbes() {
        CircuitBreaker breaker = breakerProvider.getIfAvailable();
        if (breaker != null && breaker.reapExpiredProbe()) {
            log.debug("released an expired HALF_OPEN probe lease on {}", breaker.name());
        }
    }
}
