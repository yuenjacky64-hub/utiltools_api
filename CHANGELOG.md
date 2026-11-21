# Changelog

## [v0.139.0] - 2026-11-21

- Add `GET /api/v1/utils/jobs/quarantine-status` (`ApiResponse<JobsQuarantineStatusResponse>`): read-only aggregate of the batch-hash auto-failover budget, auto-failover-exhausted non-terminal jobs, `FAILED`/`EXECUTION_LEASE_EXPIRED` jobs and `QUEUED` jobs.
- Add `GET /api/v1/utils/contact/submissions/quarantine-status` (`ApiResponse<ContactQuarantineStatusResponse>`): read-only aggregate of `utiltools.contact.max-redrives`, the `DEAD_LETTERED` count, the redrive-exhausted subset, the pending `QUEUED`/`DISPATCHING` count and the pending dispatch gate.
- Both projections run under the per-service `registryLock`, return counts only, and never mutate a row, dispatch work or open a `CONTACT_SUBMIT` journal entry; no new `@Scheduled` pass, `MaintenancePass` constant or property default.
- README: document both routes and keep the test-count badge/prose in lockstep.

