package com.example.utiltools.resilience;

import java.time.Instant;
import java.util.List;

public record ResilienceResponse(
        String name,
        CircuitState state,
        int consecutiveFailures,
        int consecutiveSuccesses,
        Instant resetAt,
        int auditCapacity,
        List<AuditEntry> audit
) {}
