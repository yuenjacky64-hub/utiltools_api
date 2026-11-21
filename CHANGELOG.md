# Changelog

## [v0.137.0] - 2026-11-21

- `AuditJournalService` now admits a bounded automatic retry for an `IN_PROGRESS` replay claim whose lease lapsed: the attempt counter increments, the owner token is cleared, the entry stays `SUCCEEDED` and is re-stamped with `replay.nextRetryAt` (REQ-01).
- Capped exponential backoff `delay = min(max, base * 2^(attempt-1))` (defaults 1000 ms / 60000 ms, ten-year ceiling, overflow-guarded) bounds the retry budget (REQ-02).
- At `replayLeaseQuarantineThreshold` the entry transitions to `REPLAY_LEASE_QUARANTINED` with exactly one tombstone and becomes permanently ineligible for replay: further `POST /api/v1/utils/audit/entries/{id}/replay` calls answer `409 REPLAY_CONFLICT` (REQ-03, REQ-04).
- Every retry admission, backoff reschedule and quarantine upgrade is journalled deterministically (`retry N of M at <deadline>`), and the verify sweep reports `BACKOFF_PENDING` while a retry is not yet due (REQ-05).

