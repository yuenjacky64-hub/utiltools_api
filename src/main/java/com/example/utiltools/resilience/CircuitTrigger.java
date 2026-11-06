package com.example.utiltools.resilience;

public enum CircuitTrigger {
    FAILURE_THRESHOLD_REACHED,
    COOLDOWN_ELAPSED,
    PROBE_FAILED,
    PROBE_SUCCEEDED,
    PROBE_LEASE_EXPIRED
}
