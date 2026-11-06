# Changelog

## [v0.28.0] - 2026-11-06

- Add scheduled background journal reaper (`utiltools.audit.cleanup-interval-ms`, default 60000 ms): reaps expired replay claims, stale PENDING entries and oversize-request entries under the journal lock
- Record one bounded `REAPED` audit tombstone per reap (`ReapRecord`/`ReapReason`) without mutating surviving entries' result, completedAt or replayable flag

