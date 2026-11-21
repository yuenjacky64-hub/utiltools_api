# Changelog

## [v0.130.0] - 2026-11-21

- Add per-pass CLOSED/OPEN/HALF_OPEN quarantine around all eight @Scheduled maintenance passes via MaintenancePassGuard (one resilience.CircuitBreaker named maintenance-passes plus a bounded AuditTrail).
- Add utiltools.scheduling.pass-failure-threshold (3, 1..100), pass-success-threshold (1, 1..10), pass-cooldown-ms (60000, >= 0) and pass-probe-lease-ms (10000, > 0) with startup validation naming each property.
- Add read-only GET /api/v1/utils/maintenance/passes returning per-pass state, counters, timestamps, nextProbeAt, auditSize/auditCapacity and a bounded recentTransitions list.
- No new Maven dependency; MaintenanceTaskScheduler pool/daemon/termination semantics and the /scheduler snapshot payload are unchanged.

