# Changelog

## [v0.100.0] - 2026-11-11

- `POST /api/v1/utils/datetime/range` enumerates a bounded (≤1000 points), ordered list of `yyyy-MM-dd` dates with `DAYS`/`WEEKS`/`MONTHS`/`YEARS` unit, `step` 1–1000 and inclusive/exclusive end.
- Points are computed from `startDate` (`startDate.plus(i * step, unit)`), so month-end and leap-day clamping stay anchored to the start.
- Calls are journalled as deterministic `OperationType.DATETIME_RANGE` and replay to `VERIFIED` without appending a second entry.
- Rejections split by layer: bean-validation failures return HTTP 400 `VALIDATION_ERROR`, service rejections HTTP 400 `BAD_REQUEST`.

