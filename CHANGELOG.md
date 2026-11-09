# Changelog

## [v0.70.0] - 2026-11-09

- Add `POST /api/v1/utils/datetime/business-days`: signed business-day arithmetic in a target IANA timezone skipping weekends and an optional `yyyy-MM-dd` holiday list, DST wall-clock preserving (weekend/holiday skipped counters reported separately).
- Extend the audit/replay infrastructure with `OperationType.DATETIME_BUSINESS_DAYS(true)`, `AuditedOperations.businessDays(...)` and a deterministic `replayInvoke` case.
- Add `DateTimeBusinessDaysRequest`/`DateTimeBusinessDaysResponse` DTOs with `@NotBlank input`, optional timezone/custom pattern, `@Min(-10000) @Max(10000) amount` and `@Size(max=366) holidays`.

