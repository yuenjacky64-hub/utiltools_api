# Changelog

## [v0.34.0] - 2026-11-07

- Add per-attempt execution lease (utiltools.jobs.execution-lease-seconds, default 30) renewed between items and a scheduled reaper (utiltools.jobs.execution-reaper-interval-ms, default 5000).\n- Reap an expired RUNNING attempt to FAILED with EXECUTION_LEASE_EXPIRED, clearing results and the lease; idempotent and fenced against late workers.\n- Expose executionLeaseExpiresAt and lastHeartbeatAt on BatchHashJobResponse while RUNNING; retry after a reap proceeds at attempt+1.

