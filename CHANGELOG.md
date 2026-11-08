# Changelog

## [v0.52.0] - 2026-11-08

- Add read-only `GET /api/v1/utils/audit/reaps` returning the newest-first reaper tombstone log with an optional `reason` filter (`limit` default 50, clamped to `utiltools.audit.max-entries`).

