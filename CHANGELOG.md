# Changelog

## [v0.25.0] - 2026-11-06

- Journal `POST /api/v1/utils/datetime/shift` through `AuditedOperations` as the deterministic `DATETIME_SHIFT` operation.
- Store the full shift request (input, targetTimeZone, amount, unit, customPattern); an unknown timezone now journals `FAILED`/`BAD_REQUEST` while the endpoint still returns HTTP 400.
- Replay of a `SUCCEEDED` shift entry returns `VERIFIED` and leaves the original result/completedAt untouched.
- README audit section enumerates every journalled operation instead of claiming blanket coverage.

