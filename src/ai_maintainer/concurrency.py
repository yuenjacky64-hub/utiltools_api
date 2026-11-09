"""Concurrency seams for the maintainer cycle engine (UTILTOOLS-77).

Two things live here and nothing else:

* :class:`CycleAdmissionGate` - the slot-counted admission gate that bounds how
  many cycle bodies may run at once (``max_concurrent_cycles``). It owns its own
  :class:`threading.Condition`, so a blocked waiter holds **no** engine lock
  (INV-CONC-1) and cannot deadlock ``shutdown()``, whose wait loop also holds no
  engine lock. Admission is *priority ordered*: a queued ``COMPLEX`` waiter wins
  the next freed slot over a queued ``EASY`` waiter, ties resolving to the
  lowest ticket (FIFO). The engine passes ``enforce_priority`` so the existing
  ``priority_preemption_enabled`` switch degrades the gate to strict FIFO.
* :class:`CycleSnapshot` - the per-cycle capture of the shared state a cycle
  could leak into a peer: the memory document's three collections, the
  watcher's active task IDs and the raw heartbeat bytes.

The module imports only the stdlib plus the ``Difficulty`` order that already
lives in :mod:`ai_maintainer.tasks.curriculum`, so it cannot form an import
cycle with the engine it serves.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from ai_maintainer.shutdown import SHUTDOWN_POLL_INTERVAL_SEC
from ai_maintainer.tasks.curriculum import Difficulty

__all__ = [
    "CycleAdmissionGate",
    "CycleSnapshot",
    "HEARTBEAT_READ_LIMIT",
    "current_heartbeat",
    "read_heartbeat",
    "write_heartbeat",
]

#: Defensive bound on any single read of the caller-supplied heartbeat document
#: (mirrors ``status.STATUS_READ_LIMIT``).
HEARTBEAT_READ_LIMIT = 65536


@dataclass(frozen=True)
class CycleSnapshot:
    """One cycle's capture of the shared state it must not leak into a peer."""

    cycle: int
    records: dict[str, list[Any]] = field(default_factory=dict)
    active_task_ids: tuple[str, ...] = ()
    heartbeat: tuple[Path, bytes | None] | None = None
    admitted_at: float = 0.0


class CycleAdmissionGate:
    """Slot-counted, priority-ordered admission gate for cycle bodies.

    ``limit`` is ``MaintainerConfig.max_concurrent_cycles``. ``sleep`` is the
    injected wait seam (the engine's fake-clock-friendly ``_sleep``) and
    ``enforce_priority`` is a callable returning the live
    ``priority_preemption_enabled`` value, so flipping the config field to
    ``False`` turns the gate into a strict FIFO queue without rebuilding it.
    """

    def __init__(
        self,
        limit: int = 2,
        *,
        sleep: Callable[[float], Any] | None = None,
        enforce_priority: Callable[[], bool] | None = None,
    ) -> None:
        self._limit = int(limit)
        self._sleep: Callable[[float], Any] = (
            sleep if sleep is not None else time.sleep
        )
        self._enforce_priority: Callable[[], bool] = (
            enforce_priority if enforce_priority is not None else (lambda: True)
        )
        self._condition = threading.Condition()
        self._active: dict[int, int] = {}  # token -> cycle
        self._waiters: list[tuple[int, Difficulty, int]] = []  # (ticket, diff, cycle)
        self._next_ticket = 0

    # ------------------------------------------------------------------ public

    @property
    def limit(self) -> int:
        return self._limit

    def acquire(
        self,
        difficulty: Difficulty,
        cycle: int,
        *,
        should_stop: Callable[[], bool] | None = None,
    ) -> int | None:
        """Take a slot for ``cycle``; ``None`` when a stop is observed first.

        The waiter registers itself, then loops: it takes a slot only when a
        slot is free **and** no *other* waiting ticket carries a strictly higher
        difficulty (ties go to the lower ticket, i.e. FIFO). While it waits it
        polls ``should_stop()`` on every wake-up (at most
        :data:`~ai_maintainer.shutdown.SHUTDOWN_POLL_INTERVAL_SEC` apart), so one
        ``request_stop()`` releases every waiter instead of stranding it.
        """

        level = Difficulty.parse(difficulty)
        if self._limit < 1:  # defensive: config validation already rejects this
            return None
        with self._condition:
            ticket = self._next_ticket
            self._next_ticket += 1
            self._waiters.append((ticket, level, int(cycle)))
            try:
                while True:
                    if should_stop is not None and should_stop():
                        return None
                    if len(self._active) < self._limit and self._is_head(ticket):
                        self._active[ticket] = int(cycle)
                        self._condition.notify_all()
                        return ticket
                    self._condition.wait(SHUTDOWN_POLL_INTERVAL_SEC)
            finally:
                # Deregister on every exit path: admitted, stopped or raised.
                self._waiters = [
                    waiter for waiter in self._waiters if waiter[0] != ticket
                ]
                self._condition.notify_all()

    def release(self, token: int | None) -> None:
        """Free the slot held by ``token``; an unknown token is a no-op."""

        if token is None:
            return
        with self._condition:
            self._active.pop(int(token), None)
            self._condition.notify_all()

    def state(self) -> dict[str, Any]:
        """Read-only admission view for ``MaintainerEngine.snapshot()``."""

        with self._condition:
            return {
                "limit": self._limit,
                "active": len(self._active),
                "waiting": [
                    {"cycle": cycle, "difficulty": level.name}
                    for _ticket, level, cycle in sorted(self._waiters)
                ],
            }

    # ----------------------------------------------------------------- private

    def _is_head(self, ticket: int) -> bool:
        """May ``ticket`` take a free slot right now?"""

        mine = next(
            (level for candidate, level, _cycle in self._waiters if candidate == ticket),
            None,
        )
        if mine is None:
            return False
        if not bool(self._enforce_priority()):
            # Operator opt-out: strict FIFO, the lowest live ticket wins.
            return min(candidate for candidate, _l, _c in self._waiters) == ticket
        for candidate, level, _cycle in self._waiters:
            if candidate != ticket and level > mine:
                return False
        return True

def read_heartbeat(path: Path | None) -> tuple[Path, bytes | None] | None:
    """Capture the raw heartbeat document for a snapshot (never raises).

    Returns ``None`` when no heartbeat path was injected, so a caller that did
    not opt into heartbeat handling gets exactly the pre-task behavior: the
    engine never invents a heartbeat file.
    """

    if path is None:
        return None
    target = Path(path)
    try:
        with target.open("rb") as handle:
            data = handle.read(HEARTBEAT_READ_LIMIT + 1)
    except FileNotFoundError:
        return (target, None)
    except OSError:
        return (target, None)
    return (target, data)


def write_heartbeat(path: Path, data: bytes) -> bool:
    """Restore previously captured heartbeat bytes; ``False`` when it failed."""

    target = Path(path)
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".tmp")
        with temporary.open("wb") as handle:
            handle.write(data)
        temporary.replace(target)
    except OSError:
        return False
    return True


def current_heartbeat(path: Path) -> bytes | None:
    """Current raw bytes of ``path`` (``None`` when absent or unreadable)."""

    captured = read_heartbeat(path)
    return None if captured is None else captured[1]
