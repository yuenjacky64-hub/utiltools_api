# Changelog

## [v0.126.0] - 2026-11-13

- Add `CycleScheduler`: a bounded pool of concurrent cycle slots with acquire/release semantics, scheduler-authoritative capacity, and per-pool workers
- Priority aging: older queued cycles overtake newer higher-priority ones after `prioritization_aging_interval_ms`
- Preemption: when the pool is saturated, a higher-priority cycle aborts the lowest-priority running cycle (`attribute_preemption` links the victim)
- `MaintainerEngine.run_forever` runs cycles through the scheduler and drains in-progress cycles on `_stop_requested`, reporting `interrupted_by` STOP; stop and preemption channels stay separate
- New config knobs `max_concurrent_cycles`, `cycle_pool_size`, `scheduler_drain_timeout_sec` (boundary-validated); 17 new tests in `tests/test_cycle_scheduler.py`
- Resolves the PR #113 base-branch conflict: the feature branch merged `origin/main` (6192d47) forward, preserving both config-knob families

