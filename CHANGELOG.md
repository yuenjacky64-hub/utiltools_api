# Changelog

## [v0.47.0] - 2026-11-07

- Add POST /api/v1/utils/jobs/{jobId}/heartbeat for running workers to renew their execution lease
- Extend executionLeaseExpiresAt by utiltools.jobs.execution-lease-seconds and stamp lastHeartbeatAt on success
- Record one bounded HEARTBEAT audit row ("lease renewed via heartbeat") per renewal, fenced by attempt generation
- Unified error envelopes: 404 JOB_NOT_FOUND, 409 JOB_RETRY_CONFLICT, 400 BAD_REQUEST for expired leases

