# Changelog

## [v0.78.0] - 2026-11-09

- **AI Maintainer: concurrent cycle execution (UTILTOOLS-77).** New `max_concurrent_cycles` config field (default 2, env `MAINTAINER_MAX_CONCURRENT_CYCLES`, validated `>= 1`) bounds how many maintenance cycles run at once via a slot admission gate. Per-cycle snapshot/restore isolates memory records, watcher active task IDs and heartbeat bytes so overlapping cycles cannot leak state into each other; difficulty-ordered admission gives a contested slot to the higher-difficulty cycle (`COMPLEX > HARD > MEDIUM > EASY`); `request_stop()` and the stop path terminate and wind down every in-flight cycle with PID/heartbeat/file-lock cleanup intact; memory records are stamped with their originating cycle id under a store write lock. No new API endpoints or framework changes.

