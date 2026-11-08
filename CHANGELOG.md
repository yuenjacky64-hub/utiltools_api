# Changelog

## [v0.54.0] - 2026-11-08

- Add `utiltools.jobs.max-retained-jobs` (default 1000) with startup rejection of values < 1.
- Enforce the terminal-row count bound under `registryLock` at every terminal transition, before publishing in `submit()` and at the tail of `evictExpiredJobs(Instant)`.
- Evict oldest `completedAt` first with a deterministic `jobId` tie-break; remove the idempotency mapping only when it still points at the victim.
- Make duplicate-key lookup and count-pressure eviction linearizable (no more 202 for an already-evicted job id).

