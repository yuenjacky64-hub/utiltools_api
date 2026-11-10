# Changelog

## [v0.96.0] - 2026-11-10

- Add read-only `GET /api/v1/utils/audit/quarantine-status` on `AuditController` returning the effective `utiltools.audit.replay-lease-quarantine-threshold`, the count of `REPLAY_LEASE_QUARANTINED` tombstones in the bounded reap log, and the sum of per-entry replay-lease expiration counters.
- Backed by `AuditJournalService.quarantineStatus()` (single `synchronized (lock)` pass, `O(max-entries)`); no reap pass runs and no entry, claim or tombstone is mutated.
- New `audit/dto/QuarantineStatusResponse` record (three ints only — no entry id, detail, stored request/result, owner token or lease deadline) inside the standard `ApiResponse` envelope.

