# Changelog

## [v0.20.0] - 2026-11-06

- `POST /api/v1/utils/datetime/shift` now routes through `AuditedOperations.shift(...)`, so every shift attempt is journalled.
- Added `OperationType.DATETIME_SHIFT(true)` (tenth journalled operation) and a `DATETIME_SHIFT -> dateTimeService.shift(...)` case in `replayInvoke`; replay of a SUCCEEDED shift entry yields `VERIFIED` without mutating the original result or `completedAt`.
- Journal entries store the full shift request (`input`, `targetTimeZone`, `amount`, `unit`, `customPattern`); unknown timezones still return HTTP 400 BAD_REQUEST and now record a FAILED DATETIME_SHIFT entry.
- README audit section enumerates the ten journalled operations and carves out the read-only timezones catalogue.

