# Changelog

## [v0.72.0] - 2026-11-09

- `POST /api/v1/utils/json/query` accepts optional `allowStaleFallback` (default false); on `503 CIRCUIT_OPEN` an opted-in caller may receive a TTL-bounded last-known-good result marked `staleFallback=true` with `cachedAt` / `cacheAgeMillis`.
- `JsonQueryFallbackCache`: SHA-256 keyed on normalized `json`+`jsonPath`, oldest-first eviction, scheduled expiry sweep; new properties `utiltools.resilience.fallback-cache-ttl-ms` (60000), `-max-entries` (256), `-sweep-interval-ms` (30000).
- Opt-in calls are journalled as non-deterministic `OperationType.JSON_QUERY_FALLBACK` and rejected for replay with `NON_DETERMINISTIC`; the fallback path never mutates breaker state and fail-closed behaviour is unchanged.

