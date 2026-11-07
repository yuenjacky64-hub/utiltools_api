# Changelog

## [v0.40.0] - 2026-11-07

- Add `utiltools.jobs.max-active-jobs-per-client` (default `20`): caps one client's non-terminal (`QUEUED`/`RUNNING`) batch-hash jobs, keyed by the same identity the rate limiter resolves. Values below `1` fail startup; a client's counter entry is dropped at zero.
- Reserve the slot atomically with job + idempotency registration inside the `registryLock` section; duplicate submissions that replay the same `idempotencyKey` and payload return the existing job instead of a quota error, and `IDEMPOTENCY_CONFLICT` still wins over quota.
- Release exactly once per reservation via a per-job atomic flag on worker `COMPLETED`, worker `FAILED`, scheduler-reaper `FAILED` and `QUEUED` cancellation; a late worker fenced by the reaper cannot release twice and eviction never releases.
- Roll back registration, idempotency mapping and the reservation when `executor.execute` rejects a first submission (`429 QUEUE_FULL` unchanged) or a requeued retry; retry re-acquires a slot before `FAILED -> QUEUED` and leaves the job `FAILED` with its `attempt` unchanged when the client is at the limit.
- Add `JobQuotaExceededException` mapped to HTTP `429` / `JOB_QUOTA_EXCEEDED` with bounded `details.limit` and `details.activeJobs`; no client key or quota state appears in `BatchHashJobResponse` or the audit journal.
- Document the new property and the new 429 error code in `README.md` (jobs failure-mode table, jobs configuration block, quota property table, error-code table).

