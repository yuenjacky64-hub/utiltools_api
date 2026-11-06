# Changelog

## [v0.19.0] - 2026-11-06

- `POST /api/v1/utils/audit/entries/{id}/replay` now claims the entry under the journal lock before invoking the operation, so only one replay of a SUCCEEDED entry can execute at a time.
- New replay states `IN_PROGRESS`, `VERIFIED`, `MISMATCH` and `FAILED`; legal transition is `IN_PROGRESS -> VERIFIED|MISMATCH|FAILED`.
- Failed replays are audited as `FAILED` with a bounded `detail` while the entry stays replayable; active or lost claims answer `409 REPLAY_CONFLICT`.
- Lease takeover: an expired `IN_PROGRESS` claim can be re-claimed, the stale owner's completion is rejected, new config key `utiltools.audit.replay-lease-seconds` (default 30) with an injectable `Clock`.
- `ReplayRecord` gains an optional `leaseExpiresAt` (NON_NULL) that is omitted once the replay reaches a terminal state; existing response fields unchanged.

