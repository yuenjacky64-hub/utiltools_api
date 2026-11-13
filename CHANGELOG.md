# Changelog

## [v0.127.0] - 2026-11-13

### Added

- `maintainer.max_diagnosis_budget` (default `3`) — configurable per-cycle diagnosis budget on `MaintainerConfig`, accepted through every config layer (keyword, env, `config.json`, default) and validated (`< 1` or non-int raises `ValueError`).
- Per-cycle diagnosis history in `CycleResult.issues_detected`: every attempt records `timestamp`, `error_type`, `outcome` and the `budget_state` snapshot (`limit`/`used`/`remaining`/`exhausted`).
- `DIAGNOSIS_BUDGET_EXHAUSTED` audit entry carrying the budget limit, attempts made and final outcome; the engine rolls the cycle back to `RECOVERING` instead of diagnosing again.
- `BUDGET_RESET` summary row emitted when a cycle ends `SUCCESS`, so the next cycle is re-granted the full budget.

### Tests

- `tests/test_diagnosis_budget.py` — budget enforcement, history recording, exhaustion audit, success reset (9 passed).

