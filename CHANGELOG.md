# Changelog

## [v0.62.0] - 2026-11-08

- Stale-task state machine in the maintainer poll tick: `TaskSpec.last_progress_at` / `last_progress_age_sec` / `stale_since`, `STALE_ELIGIBLE`, `stamp_progress()`, `progress_age_sec()` (`src/ai_maintainer/tasks/curriculum.py`).
- New tunables `stale_timeout_sec` (default 300 s, env `MAINTAINER_STALE_TIMEOUT_SEC`) and `stale_recovery_grace_sec` (default 60 s), validated and float-coerced (`src/ai_maintainer/config.py`).
- `MaintainerEngine._evaluate_stale()` emits one `STALE_TASK` warning per breach from `run_cycle`; `sweep_stale_tasks()` transitions a task to `FAILED` with a `STALE_TASK_RECOVERED` audit entry citing the last progress line, wired into the existing daemon sweep thread (`src/ai_maintainer/engine.py`).
- Additive to the dead-step, quarantine and preemption paths; no new loop, thread, route or persisted contract. New `tests/test_stale_task_recovery.py` (8 tests) + `tests/StaleTaskRecovery.should` shim; full suite 57 passed.

