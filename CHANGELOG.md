# Changelog

## [v0.60.0] - 2026-11-08

- Per-fingerprint circuit breaker buckets: `CircuitBreaker.execute(String, Supplier)` / `admit(String)` scope state, counters, probe generation and lease by fingerprint; the legacy `execute(Supplier)` and all read accessors resolve `Fingerprints.DEFAULT` (`default`), so existing callers are byte-identical.
- New `Fingerprints` normalisation rule (`[A-Za-z0-9_.:-]{1,64}`, else `default`) and `X-Client-Fingerprint` header on `POST /api/v1/utils/resilience/probe`.
- `QUARANTINE` circuit trigger + `fingerprint` column on audit entries: isolating a non-default fingerprint writes a `QUARANTINE` row naming it, immediately after its `FAILURE_THRESHOLD_REACHED` / `PROBE_FAILED` transition.
- `GET /api/v1/utils/resilience` now returns `data.fingerprints[]` (fingerprint, state, counters, resetAt, probeInFlight, probeLeaseExpiresAt, quarantined) alongside the unchanged global fields; bounded map (default 256 keys, CLOSED buckets evicted before quarantined).
- Tests: 12 new (`FingerprintCircuitBreakerTest` 9, `FingerprintResilienceControllerTest` 3); 549 total, 0 failures.

