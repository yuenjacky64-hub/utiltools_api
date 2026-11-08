package com.example.utiltools.resilience;

public enum CircuitTrigger {
    FAILURE_THRESHOLD_REACHED,
    COOLDOWN_ELAPSED,
    PROBE_FAILED,
    PROBE_SUCCEEDED,
    PROBE_LEASE_EXPIRED,
    /** A non-default fingerprint was isolated into {@code OPEN}; names the fingerprint it belongs to. */
    QUARANTINE
}
