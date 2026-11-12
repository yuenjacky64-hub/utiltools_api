# Changelog

## [v0.112.0] - 2026-11-12

- Added POST /api/v1/utils/datetime/overlap: half-open interval overlap detection with audit journaling (DATETIME_OVERLAP, replay VERIFIED)
- New DTOs DateTimeOverlapRequest/DateTimeOverlapResponse; reuses parseInstant auto-detect grammar; 400 on reversed/zero-length intervals
- README endpoint table + detailed §H section; journalled-op count 31 -> 32

