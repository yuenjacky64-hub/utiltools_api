# Changelog

## [v0.95.0] - 2026-11-10

- Reaper: per-entry replay-lease exhaustion counter on `JournalRecord`; entries are failed once with `REPLAY_LEASE_QUARANTINED` and skipped forever after `utiltools.audit.replay-lease-quarantine-threshold` (default 3, clamped >= 1) rollbacks
- `ReapReason.REPLAY_LEASE_QUARANTINED` + detail template; quarantines counted under `replayLeaseExpired` so the `reaped` sum invariant holds
- Tests: 2 new reaper cases (3-expiry quarantine + skip, threshold=1, dry-run parity); full suite green

