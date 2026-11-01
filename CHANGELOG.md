# Changelog

## [v0.9.0] - 2026-11-01

- Added `POST /api/v1/utils/datetime/shift` for DST-aware, month-end-clamped
  duration arithmetic (YEARS/MONTHS/WEEKS/DAYS/HOURS/MINUTES/SECONDS, signed
  amounts) inside a target IANA time zone.
- New `DateTimeShiftRequest` / `DateTimeShiftResponse` DTOs with bean
  validation (`amount` non-null and non-zero, case-insensitive `unit`
  whitelist, optional `customPattern`).
- `DateTimeService.shift(...)` reuses existing instant/timezone parsing and
  formats with `customPattern` or `ISO_OFFSET_DATE_TIME`.
- 17 controller + 23 service datetime tests (month-end clamp, DST
  spring-forward/fall-back, zero amount, unknown zone, invalid pattern,
  malformed JSON); README endpoint table and curl/response sample updated.
