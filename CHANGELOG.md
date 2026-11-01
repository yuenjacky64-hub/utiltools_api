# Changelog

## [v0.11.0] - 2026-11-01

- Add `POST /api/v1/utils/resilience/probe` and `GET /api/v1/utils/resilience` for breaker introspection and deterministic HALF_OPEN probing.
- Guard `JsonService.query` behind a CLOSED/OPEN/HALF_OPEN circuit breaker with bounded (1024-entry) audit trail.
- Map `CircuitBreakerOpenException` to HTTP 503 `CIRCUIT_OPEN` (state/resetAt/reason) instead of the generic 500 envelope.

