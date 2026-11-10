# Changelog

## [v0.85.0] - 2026-11-10

- feat(audit): add `POST /api/v1/utils/audit/reaps` operator-triggered journal reap pass
- feat(audit): optional `dryRun` preview that classifies without mutating journal state, tombstones, replay claims or manual intent
- feat(audit): optional `entryId` manual reclaim routed through `AuditJournalService.requestManualReap` under the journal lock (404 unknown id, 409 `REAP_CONFLICT` for terminal entries)
- feat(audit): new `JournalReapResponse` DTO with `dryRun`/`scanned`/`reaped` plus REPLAY_LEASE_EXPIRED, MANUALLY_REAPED, STALE_PENDING and OVERSIZE_PAYLOAD counts (no stored payloads or tokens)
- feat(common): `ReapConflictException` mapped by `GlobalExceptionHandler` to HTTP 409 `REAP_CONFLICT`

