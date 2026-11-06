# Changelog

## [v0.29.0] - 2026-11-06

- `ReapReason` gains `MANUALLY_REAPED`, declared between `REPLAY_LEASE_EXPIRED` and `STALE_PENDING` so declaration order keeps documenting the pass precedence.
- `AuditJournalService.requestManualReap(String)` records intent to reclaim a specific `PENDING` entry without changing state or writing a tombstone; `reap()` consumes it in a new branch 2 that moves the entry to `FAILED` with errorCode `MANUALLY_REAPED` and appends exactly one bounded `REAPED` tombstone (detail clamped to `ReapRecord.MAX_DETAIL_LENGTH`).
- Manual reclaim wins over the age-based stale sweep, terminal and unknown entries are never touched, and a second `reap()` appends nothing; 4 new tests in `AuditJournalReaperTest` (281 total, was 277).

