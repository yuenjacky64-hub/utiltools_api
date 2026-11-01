package com.example.utiltools.resilience;

import java.time.Duration;

public record CircuitBreakerConfig(int failureThreshold, int successThreshold, Duration cooldown) {

    public CircuitBreakerConfig {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (successThreshold < 1) {
            throw new IllegalArgumentException("successThreshold must be >= 1");
        }
        if (cooldown == null || cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must be a non-negative duration");
        }
    }

    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(5, 2, Duration.ofSeconds(30));
    }
}
