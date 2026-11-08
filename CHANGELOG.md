# Changelog

## [v0.59.0] - 2026-11-08

- Per-fingerprint circuit breaking in `src/ai_maintainer/engine.py`: `FingerprintBucket` / `FingerprintCircuitState` (`CLOSED`/`OPEN`/`HALF_OPEN`) tally consecutive blocked cycles keyed by error-type fingerprint, with an exponential `_blocked_backoff_sec` and a bounded registry that never evicts quarantined buckets.
- Quarantine skips enqueue and diagnosis for the fingerprint's window (one `FINGERPRINT_SKIPPED` event per cycle); `sweep_quarantine()` expires due windows, probes outside the engine lock and closes or re-arms the circuit; `start_scheduler()`/`stop_scheduler()` run the pass on a daemon thread.
- New read-only `GET /api/v1/maintainer/engine/fingerprint-state` endpoint (`src/ai_maintainer/api.py`, stdlib `ThreadingHTTPServer`) returning per-fingerprint `quarantine_active`, `consecutive_blocked`, `circuit_state`, `quarantine_until` and `remaining_sec`; 404/405 handled without leaking exception text.
- Six `MAINTAINER_FINGERPRINT_*` / registry knobs in `config.py` coerced from env and validated in `__post_init__`.

