# Changelog

## [v0.86.0] - 2026-11-10

- `AuditJournalService.renewReplayLease(id, ownerToken)`: lock-held, owner-fenced lease renewal that only extends `leaseExpiresAt` for the live `IN_PROGRESS` owner and mutates nothing on a stale token, unknown entry, terminal claim or expired lease.
- New `utiltools.audit.replay-heartbeat-seconds` (default half of `replay-lease-seconds`, minimum 1, validated positive at startup); `AuditReplayService` renews on that cadence while `replayInvoke` runs and cancels the schedule in a `finally` block.
- Long replays that outlive the lease now finish `VERIFIED`/`MISMATCH` instead of `409 REPLAY_CONFLICT`, and no `REPLAY_LEASE_EXPIRED` tombstone is recorded; a renewal racing a reap still resolves under the journal lock so exactly one wins.
- Owner tokens stay private in `list`/`find`/`replay` responses; only `leaseExpiresAt` is exposed. README and OpenAPI text document the renewal behaviour. No new dependency, no test sleeps for the lease duration.

