"""Small, dependency-free helpers shared by the engine and the CLI.

UTILTOOLS-102 adds the instance-lock contract here so the daemon
(:mod:`ai_maintainer.engine`) and the operator tool (:mod:`cli`) resolve and
probe the *same* lock file through one implementation (INV-LOCK-1).
"""

from __future__ import annotations

from ai_maintainer.util.fs import (
    DEFAULT_INSTANCE_LOCK_FILE,
    LOCK_PROBE_FREE,
    LOCK_PROBE_HELD,
    LOCK_PROBE_UNAVAILABLE,
    InstanceLock,
    LockProbe,
    MaintainerAlreadyRunning,
    pid_is_maintainer,
    probe_lock,
    read_owner_pid,
    resolve_lock_path,
)

__all__ = [
    "DEFAULT_INSTANCE_LOCK_FILE",
    "LOCK_PROBE_FREE",
    "LOCK_PROBE_HELD",
    "LOCK_PROBE_UNAVAILABLE",
    "InstanceLock",
    "LockProbe",
    "MaintainerAlreadyRunning",
    "pid_is_maintainer",
    "probe_lock",
    "read_owner_pid",
    "resolve_lock_path",
]
