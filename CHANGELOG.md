# Changelog

## [v0.22.0] - 2026-11-06

- `CircuitBreaker` replaces the bare `probeInFlight` boolean with an active probe generation plus `probeLeaseExpiresAt` deadline; a second HALF_OPEN caller inside a live lease still fast-fails with 503 `CIRCUIT_OPEN` "probe already in flight", but an expired lease admits a new generation instead of locking the breaker out forever.
- Superseded probe generations are fenced: `onDelegateSuccess` / `onDelegateFailure` no-op unless the caller owns the current unexpired generation, so a late success or failure changes no counter, state edge, or audit row. Nested guarded calls stay pass-through via `ThreadLocal<Long>`.
- New `CircuitTrigger.PROBE_LEASE_EXPIRED` audit trigger and public idempotent `reapExpiredProbe()`, scheduled by `utiltools.resilience.probe-reaper-interval-ms` (default 5000).
- `GET /api/v1/utils/resilience` now exposes `probeInFlight` and `probeLeaseExpiresAt`; `utiltools.resilience.probe-lease-seconds` (default 10) is documented in README and `application.properties`.

