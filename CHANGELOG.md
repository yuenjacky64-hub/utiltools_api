# Changelog

## [v0.10.0] - 2026-11-01

- Add `version=V4|V7` query parameter to `GET /api/v1/utils/crypto/uuid` (case-insensitive, default `V4`); `UuidResponse` gains an additive `version` field.
- Implement RFC 9562 UUIDv7 in `CryptoService`: 48-bit unix-millisecond prefix, version nibble `7`, `0b10` variant, and a 62-bit monotonic counter in the random suffix guarded by an `AtomicLong` CAS loop (unique, sortable ids under concurrency).
- Unknown `version` values return the unified HTTP 400 `BAD_REQUEST` envelope (never 500).
- Tests: fixed-clock v7 vector, clock-regression monotonicity, compact v7, 8-thread x 10000 duplicate-free concurrency invariant, MockMvc v7/compact/400 rows (40/40 suite green).
- README documents the new parameter, response shape, and test inventory.

