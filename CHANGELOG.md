# Changelog

## [v0.75.0] - 2026-11-09

- Add `SHUTTING_DOWN`/`STOPPED` engine states and a bounded wind-down in `MaintainerEngine.run_forever()` (`EngineState`, `should_stop()`).
- Add `MaintainerConfig.max_shutdown_wait_sec` (default 300 s, env `AI_MAINTAINER_MAX_SHUTDOWN_WAIT_SEC`, negative/non-finite rejected).
- Persist in-flight task state to `_shutdown_state.json` per task slot (role, status, timeline, elapsed wait) with an atomic write and symlink/escape guard; `read_shutdown_state()` restores it after restart.
- Drain every registered file lock exactly once (a raising `release()` cannot strand the rest).
- Append one JSON audit line per process wind-down to `data/maintainer_shutdown.log`; memoised report makes repeat calls idempotent.

