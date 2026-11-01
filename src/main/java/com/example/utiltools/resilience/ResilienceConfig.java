package com.example.utiltools.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker jsonPathCircuitBreaker() {
        Clock clock = Clock.systemUTC();
        return new CircuitBreaker("json-query", CircuitBreakerConfig.defaults(), new AuditTrail(1024, clock), clock);
    }
}
