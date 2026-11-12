# Changelog

## [v0.117.0] - 2026-11-12

- New: bounded shared scheduler pool (utiltools.scheduling.*) for all eight @Scheduled maintenance passes; daemon threads, graceful close
- New: read-only GET /api/v1/utils/maintenance/scheduler status route (pool size, active/completed counts, queue depth)
- Docs: utiltools.scheduling.* defaults and validation documented in application.properties and README

