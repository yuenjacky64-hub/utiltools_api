# Changelog

## [v0.123.0] - 2026-11-13

- Add `CycleScheduler` with a bounded pool of concurrent cycle slots (acquire/release, gate-authoritative capacity, per-pool workers)
- Add priority aging so older queued cycles overtake newer higher-priority ones after `prioritization_aging_interval_ms`
- Add preemption: a higher-priority cycle aborts the lowest-priority running cycle when the pool is saturated, with `attribute_preemption` linking the victim
- Split stop and preemption channels; `MaintainerEngine.run_forever` drains in-progress cycles on `_stop_requested` and reports `interrupted_by` STOP
- New config: `max_concurrent_cycles`, `cycle_pool_size`, `scheduler_drain_timeout_sec` (validated); 17 new tests in `tests/test_cycle_scheduler.py`
