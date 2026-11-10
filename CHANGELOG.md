# Changelog

## [v0.87.0] - 2026-11-10

- Add read-only `GET /api/v1/utils/datetime/week?date=yyyy-MM-dd` backed by `WeekFields.ISO`, returning `date`, `isoWeekYear`, `isoWeek`, `dayOfWeek`, `dayOfYear` and `weekend` (week-year rollover included: `2024-12-30` is `2025-W01`).
- New `DateTimeWeekResponse` record and pure `DateTimeService.week(String)` (no clock, zone or locale read); null/blank/malformed dates raise `IllegalArgumentException` mapped by `GlobalExceptionHandler` to `400 BAD_REQUEST`.
- Route is read-only and not journalled: no new `OperationType`, no `AuditedOperations` call, no `RouteCostResolver` entry, no new dependency.

