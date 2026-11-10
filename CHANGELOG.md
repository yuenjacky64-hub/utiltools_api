# Changelog

## [v0.89.0] - 2026-11-10

- `MaintainerEngine._monitor_until_terminal` exits with `MONITOR_STOPPED` within one poll interval of `request_stop()` (REQ-01).
- Cooperative cancellation API for running tasks: `cancel_signal` / `check_cancel()` / `check_cancel_now()` / `check_cancel_reason()` + `CooperativeCancel`, so a step finalizes in-progress work instead of being force-terminated; non-cooperating tasks are coerced in bookkeeping only (`TASK_FORCE_STOPPED`, sink state `CANCELLED`) (REQ-02).
- `run_forever()` guarantees `maintainer.pid` / `maintainer.heartbeat` teardown via a `finally`-style block on every exit path, including a raising wind-down (REQ-03).
- `_outcome_for()` maps the stop family (`stopped`/`stop`/`cancelled`/`SIGNAL_*`) to `INTERRUPTED` and is total over unknown reasons (REQ-04).

