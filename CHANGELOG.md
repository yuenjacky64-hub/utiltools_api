# Changelog

## [v0.16.0] - 2026-11-01

- `AuditJournalService` gains a public clock-free `int reapStalePending(Instant cutoff)` sweep that, under the existing journal lock, transitions every still-`PENDING` entry older than the cutoff to `FAILED` with `errorCode STALE_PENDING_REAPED` and stamps `completedAt` at the reap instant (REQ-01, REQ-03).
- New `@Scheduled(fixedDelayString = "${utiltools.audit.cleanup-interval-ms:60000}")` wrapper calls the sweep with `Instant.now(clock).minusSeconds(stalePendingSeconds)`, mirroring the jobs / rate-limiter reaper pattern; the threshold is constructor-injected from `@Value("${utiltools.audit.stale-pending-seconds:300}")` and clamped to >= 1s (REQ-02).
- `utiltools.audit.stale-pending-seconds=300` and `utiltools.audit.cleanup-interval-ms=60000` added to `application.properties` (REQ-04).
- Six new tests in `AuditJournalServiceTest` cover reap, idempotence, terminal immunity, the no-op wrapper, post-reap transition rejection and the sweep-vs-concurrent-`begin` lock invariant; full reactor 173/173 green (REQ-05).

