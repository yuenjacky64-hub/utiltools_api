# Changelog

## [v0.6.0] - 2026-11-01

- feat(datetime): add GET /api/v1/utils/datetime/timezones returning a bounded, lexicographically sorted IANA timezone catalog with current UTC offsets
- feat(datetime): case-insensitive trimmed prefix filter, default limit 50, max 200, single captured instant for all offsets
- fix(common): map non-numeric query-parameter coercion to HTTP 400 BAD_REQUEST instead of 500
- test(datetime): add service and MockMvc coverage for sorting, filtering, truncation, Tokyo offset (+09:00/32400) and limit rejection
- docs: document the timezone catalog endpoint with curl example and sample response

