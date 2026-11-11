# Changelog

## [v0.101.0] - 2026-11-11

- Added a process-lifetime advisory instance lock (data/maintainer.lock, fcntl.flock) so at most one live maintainer loop can own a MAINTAINER_ROOT data directory; a concurrent start exits non-zero naming the live owner instead of building a second loop
- New start and run-once CLI verbs; status/stop now probe the lock (not just maintainer.pid) to identify the real owner; a SIGKILLed owner leaves a stale pid that reports STOPPED and is replaced without manual cleanup
- Ownership-safe exit paths: a leaving daemon no longer unlinks a successor's maintainer.pid / maintainer.heartbeat files; lock release on cycle exception and stop-flag clearing are tied to lock ownership
- New AI_MAINTAINER_INSTANCE_LOCK_FILE config knob (default data/maintainer.lock; blank value disables locking); 20 new lock/CLI tests plus full regression suite green

