# Changelog

## [v0.13.0] - 2026-11-01

- Add `GET /api/v1/utils/datetime/timezones` IANA timezone catalog with current offsets
- Add `TimeZoneInfo` / `TimeZoneListResponse` DTOs (timeZone, offset, offsetSeconds, localDateTime)
- Add `DateTimeService.listTimeZones` with trimmed case-insensitive prefix filter, lexicographic sort, default limit 50 / max 200, single captured instant
- Reject out-of-range and non-numeric limits with HTTP 400 `BAD_REQUEST`
- Extend DateTimeServiceTest / DateTimeControllerTest and document the endpoint in README

