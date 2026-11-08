# Changelog

## [v0.51.0] - 2026-11-08

- Add `CANCELLING` to `JobStatus`: `DELETE /api/v1/utils/jobs/{jobId}` on a RUNNING job now records a cooperative cancellation request and answers `200` with `status=CANCELLING` instead of `409 JOB_CANCELLATION_CONFLICT`. The worker honours the request at its next checkpoint and terminalizes `CANCELLED` exactly once (checkpoint and results discarded, one `JOB_CANCELLED` audit close, one quota release).
- Lease reaper and the drain path agree on `CANCELLING`: a lapsed lease or a service stop terminalizes to `CANCELLED`, so a stuck cancellation is always recoverable; repeated `DELETE` on `CANCELLING`/`CANCELLED` is idempotent, and `COMPLETED`/`FAILED` still conflict.
- README job state machine, cancellation, error and quota tables plus the controller `@Operation` text updated for the new phase.

