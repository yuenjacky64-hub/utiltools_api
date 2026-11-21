package com.example.utiltools.scheduling;

import com.example.utiltools.resilience.CircuitState;
import com.example.utiltools.resilience.CircuitTrigger;
import java.time.Instant;
import java.util.List;

/**
 * Body of {@code GET /api/v1/utils/maintenance/passes} (REQ-05).
 *
 * <p>One {@link Row} per {@link MaintenancePass}, plus the guard's audit bounds and a bounded list
 * of recent transitions (at most {@link MaintenancePassGuard#MAX_RECENT_TRANSITIONS} rows). The
 * payload is a pure projection of read-only guard accessors, so polling it never advances a
 * cooldown, claims a probe lease or moves a counter.</p>
 */
public record MaintenancePassesResponse(
        List<Row> passes,
        int auditSize,
        int auditCapacity,
        List<Transition> recentTransitions
) {

    /** One pass: circuit state, quarantine telemetry and the probe deadline. */
    public record Row(
            String pass,
            CircuitState state,
            int consecutiveFailures,
            String lastOutcome,
            Instant lastFailureAt,
            Instant lastSuccessAt,
            Instant nextProbeAt,
            long succeeded,
            long failed,
            long skipped
    ) {
    }

    /** One audit row from the guard's bounded {@code AuditTrail}. */
    public record Transition(
            Instant at,
            String pass,
            CircuitState from,
            CircuitState to,
            CircuitTrigger trigger
    ) {
    }
}
