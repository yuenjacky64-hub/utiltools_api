# Changelog

## [v0.77.0] - 2026-11-09

- `MaintainerEngine.cancel_task()` returns a `CancelReport` (`cancelled`/`timed_out`/`waited_sec`) bounded by `cancel_timeout_sec` (REQ-01).
- `request_stop()` / `should_stop()` now short-circuit the cycle loop and `run_forever`, which logs the halt reason and emits `STOP_HONOURED` (REQ-02, REQ-04).
- `pid_file`/`heartbeat_file` (and `.tmp` siblings) are removed on stop and on natural completion; stale task slots are pruned; `record_runs`/`bug_fixes`/`learnings` are pruned to the `memory_max_*` limits and persisted (REQ-03).
- New `cli.py stop` command clears stale markers, validates the pid file and waits a bounded timeout (REQ-06).
- New `tests/test_engine.py` (14 tests) plus 5 `stop` tests in `tests/test_cli.py` (REQ-05).

