"""Graceful wind-down seam for the maintainer engine.

The engine's only checkpoint is the cycle boundary, so a wind-down cannot cancel
an in-flight step: it publishes a stop signal, waits (bounded) for the cycle to
leave ``run_cycle()``, then persists the in-flight task state, releases every
tracked file lock and appends exactly one audit line.

Everything that touches the disk lives here so ``engine.py`` keeps its no-I/O
property. The two write idioms are deliberate local mirrors of
``consistency.py`` (atomic temp-file-then-``os.replace`` and "guard the slot you
write into"); importing a sibling module's private helper would couple two
private surfaces.

Doctrine: absent evidence is never a guess. ``read_shutdown_state()`` returns
``None`` for a missing, unreadable or non-object document, and a task whose role
was never observed is persisted with ``role: null`` / ``role_source:
"unobserved"`` rather than an invented role.
"""

from __future__ import annotations

import json
import os
import signal
import time
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Mapping

__all__ = [
    "CLEANUP_CLEAR_RUNTIME_MARKERS",
    "CLEANUP_PERSIST_TASK_STATE",
    "CLEANUP_PRUNE_MEMORY",
    "CLEANUP_PRUNE_STALE_TASK_SLOTS",
    "CLEANUP_RELEASE_FILE_LOCKS",
    "CLEANUP_STOP_SCHEDULER",
    "CLEANUP_WRITE_SHUTDOWN_AUDIT",
    "EngineState",
    "PERSIST_SKIPPED_NO_STATE_ROOT",
    "SHUTDOWN_AUDIT_NAME",
    "SHUTDOWN_AUDIT_SCHEMA",
    "SHUTDOWN_POLL_INTERVAL_SEC",
    "SHUTDOWN_STATE_FILE",
    "SHUTDOWN_STATE_SCHEMA",
    "SHUTDOWN_STATE_TMP",
    "ShutdownReport",
    "default_audit_path",
    "install_stop_handlers",
    "read_shutdown_state",
    "write_shutdown_audit",
    "write_shutdown_state",
]

SHUTDOWN_STATE_FILE = "_shutdown_state.json"
SHUTDOWN_STATE_TMP = "_shutdown_state.json.tmp"
SHUTDOWN_STATE_SCHEMA = "shutdown-state/v1"
SHUTDOWN_AUDIT_NAME = "maintainer_shutdown.log"
SHUTDOWN_AUDIT_SCHEMA = "maintainer-shutdown/v1"
SHUTDOWN_POLL_INTERVAL_SEC = 0.05

CLEANUP_STOP_SCHEDULER = "stop_scheduler"
CLEANUP_PERSIST_TASK_STATE = "persist_task_state"
CLEANUP_RELEASE_FILE_LOCKS = "release_file_locks"
# UTILTOOLS-76 REQ-03: three more wind-down steps, run after the lock release.
CLEANUP_CLEAR_RUNTIME_MARKERS = "clear_runtime_markers"
CLEANUP_PRUNE_STALE_TASK_SLOTS = "prune_stale_task_slots"
CLEANUP_PRUNE_MEMORY = "prune_memory"
CLEANUP_WRITE_SHUTDOWN_AUDIT = "write_shutdown_audit"

PERSIST_SKIPPED_NO_STATE_ROOT = "no state root configured"


class EngineState(str, Enum):
    """Lifecycle state of the engine process.

    The ``str`` mixin keeps event dictionaries and audit records JSON-ready.
    """

    IDLE = "IDLE"
    SLEEPING = "SLEEPING"
    RUNNING = "RUNNING"
    SHUTTING_DOWN = "SHUTTING_DOWN"
    STOPPED = "STOPPED"


@dataclass(frozen=True)
class ShutdownReport:
    """Outcome of one ``MaintainerEngine.shutdown()`` call."""

    state: EngineState
    timed_out: bool
    waited_sec: float
    active_task_ids: tuple[str, ...] = ()
    persisted_task_ids: tuple[str, ...] = ()
    released_lock_keys: tuple[str, ...] = ()
    cleanup_steps: tuple[str, ...] = ()
    failed_steps: tuple[str, ...] = ()
    lock_release_failures: tuple[str, ...] = ()
    persist_skipped_reason: str | None = None
    audit_written: bool = False
    audit_path: str | None = None

    def to_dict(self) -> dict[str, Any]:
        """Serialize to a JSON-ready mapping (keys mirror the fields 1:1)."""

        return {
            "state": self.state.value,
            "timed_out": self.timed_out,
            "waited_sec": self.waited_sec,
            "active_task_ids": list(self.active_task_ids),
            "persisted_task_ids": list(self.persisted_task_ids),
            "released_lock_keys": list(self.released_lock_keys),
            "cleanup_steps": list(self.cleanup_steps),
            "failed_steps": list(self.failed_steps),
            "lock_release_failures": list(self.lock_release_failures),
            "persist_skipped_reason": self.persist_skipped_reason,
            "audit_written": self.audit_written,
            "audit_path": self.audit_path,
        }


def default_audit_path() -> Path:
    """``<repo>/data/maintainer_shutdown.log`` (runtime artifact, git-ignored)."""

    return Path(__file__).resolve().parents[2] / "data" / SHUTDOWN_AUDIT_NAME


def write_shutdown_audit(
    path: str | Path,
    record: Mapping[str, Any],
    *,
    clock: Callable[[], float] = time.time,
) -> bool:
    """Append one JSON line to the audit log; never raises.

    Returns ``True`` when the line was written, ``False`` on any ``OSError``
    (a read-only or missing data directory must not block the wind-down).
    """

    if not isinstance(record, Mapping):
        raise ValueError("shutdown audit record must be a mapping")
    target = Path(path)
    payload = dict(record)
    payload.setdefault("recorded_at", float(clock()))
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps(payload, sort_keys=True) + "\n"
        with target.open("a", encoding="utf-8") as handle:
            handle.write(line)
            handle.flush()
            os.fsync(handle.fileno())
    except OSError:
        return False
    return True


def write_shutdown_state(
    slot_dir: str | Path,
    state_root: str | Path,
    payload: Mapping[str, Any],
) -> Path:
    """Atomically write ``_shutdown_state.json`` into one task slot."""

    if not isinstance(payload, Mapping):
        raise ValueError("shutdown state payload must be a mapping")
    slot = Path(slot_dir)
    root = Path(state_root)
    _guard_slot(slot, root)
    slot.mkdir(parents=True, exist_ok=True)
    target = slot / SHUTDOWN_STATE_FILE
    tmp = slot / SHUTDOWN_STATE_TMP
    data = json.dumps(dict(payload), sort_keys=True, indent=2) + "\n"
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(tmp, flags, 0o644)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    try:
        _guard_slot(slot, root)  # narrows the swap window
        os.replace(tmp, target)
    except OSError:
        try:
            tmp.unlink()
        except OSError:  # pragma: no cover - defensive
            pass
        raise
    return target


def read_shutdown_state(slot_dir: str | Path) -> dict[str, Any] | None:
    """Read one persisted slot document; ``None`` when it is not usable."""

    try:
        raw = (Path(slot_dir) / SHUTDOWN_STATE_FILE).read_text(encoding="utf-8")
        data = json.loads(raw)
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict):
        return None
    return data


def install_stop_handlers(
    engine: Any,
    signals: tuple[int, ...] = (signal.SIGINT, signal.SIGTERM),
) -> dict[int, Any]:
    """Route the given signals to the engine's stop flag (opt-in).

    UTILTOOLS-80 §4.2: when the engine exposes a ``_handle_signal`` seam (the
    lock-free, I/O-free flag setter), that handler is installed instead of the
    ``request_stop()`` closure - a handler that takes the engine lock can block
    behind a peer thread mid-commit. Engines without the seam keep the previous
    behaviour, so the signature and return value are unchanged.

    Returns the previous handlers so a caller can restore them. Raises
    ``ValueError`` outside the main thread, exactly like ``signal.signal``.
    """

    handler = getattr(engine, "_handle_signal", None)
    if not callable(handler):
        handler = _request_stop_handler(engine)
    previous: dict[int, Any] = {}
    for number in signals:
        previous[number] = signal.signal(number, handler)
    return previous


def _request_stop_handler(engine: Any) -> Callable[..., None]:
    def _handler(signum: int, frame: Any) -> None:  # pragma: no cover - signal path
        engine.request_stop()

    return _handler


def _guard_slot(slot_dir: Path, state_root: Path) -> None:
    """Refuse to write through a slot that is a symlink or escapes the root."""

    if slot_dir.is_symlink():
        raise OSError("symlinked task slot")
    resolved_root = state_root.resolve()
    resolved = slot_dir.resolve()
    if resolved != resolved_root and resolved_root not in resolved.parents:
        raise OSError("task slot escapes the state root")
