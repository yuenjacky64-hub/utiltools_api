# Changelog

## [v0.65.0] - 2026-11-08

- `GET /api/v1/utils/consistency-check` reports cross-store task state (signal, progress, timeline, archive) with a canonical ground truth.
- Startup recovery job repairs detected drift through a journal-before-write `CONSISTENCY_RECOVERY` audit trail (`PENDING` → `SUCCEEDED`/`FAILED`/`REFUSED`).
- Signal middleware gate refuses role transitions that would create cross-store inconsistency and logs the rejection as a compliance-gate finding.
- Manual override (`.manual_override`) refuses auto-recovery and preserves the original signal as evidence.
- `tests/test_consistency_recovery.py`: 44 cases across the five required categories, incl. the sigterm state-drift regression.

