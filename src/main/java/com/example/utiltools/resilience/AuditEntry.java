package com.example.utiltools.resilience;

import java.time.Instant;

public record AuditEntry(
        long seq,
        Instant timestamp,
        String breaker,
        CircuitState from,
        CircuitState to,
        CircuitTrigger trigger,
        String reason
) {}
