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
  UTILTOOLS-130 REQ-03 adds *aging*: a waiter's effective level grows by
  ``floor(waited / aging_interval)``, so a cycle that has waited long enough
  overtakes a newer, harder one and can never starve.
  UTILTOOLS-130 REQ-01 adds slot *inheritance* (:meth:`CycleAdmissionGate.inherit`)
  so the cycle scheduler can take a slot for a worker thread and hand it to the
  body it runs without the slot being counted - or waited for - twice.
* :class:`CycleSnapshot` - the per-cycle capture of the shared state a cycle
  could leak into a peer: the memory document's three collections, the
  watcher's active task IDs and the raw heartbeat bytes.

The module imports only the stdlib plus the ``Difficulty`` order that already
lives in :mod:`ai_maintainer.tasks.curriculum`, so it cannot form an import
cycle with the engine it serves.
"""

from __future__ import annotations

import os
import stat
import threading
import time
from dataclasses import dataclass
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
    """One cycle's capture of the shared state it must not leak into a peer.

    ``records`` is ``None`` when the capture did not happen (no store installed
    or ``snapshot_records()`` failed). ``None`` means *unknown*, never *empty*:
    a restore of an unknown baseline is a no-op, so a failed capture can never
    blank the operator's ``runs``/``bug_fixes``/``learnings``.
    """

    cycle: int
    records: dict[str, list[Any]] | None = None
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
    ``aging_interval_sec`` is the REQ-03 aging interval (a float or a callable
    returning the live value; ``0``/``None`` disables aging, which is the
    pre-UTILTOOLS-130 behaviour) and ``clock`` is the source of the wait time.
    """

    def __init__(
        self,
        limit: int = 2,
        *,
        sleep: Callable[[float], Any] | None = None,
        enforce_priority: Callable[[], bool] | None = None,
        aging_interval_sec: float | Callable[[], float] | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._limit = int(limit)
        self._sleep: Callable[[float], Any] = (
            sleep if sleep is not None else time.sleep
        )
        self._enforce_priority: Callable[[], bool] = (
            enforce_priority if enforce_priority is not None else (lambda: True)
        )
        self._aging_interval_sec = aging_interval_sec
        self._clock: Callable[[], float] = clock if clock is not None else time.monotonic
        self._condition = threading.Condition()
        self._active: dict[int, int] = {}  # token -> cycle
        # (ticket, difficulty, cycle, enqueued_at)
        self._waiters: list[tuple[int, Difficulty, int, float]] = []
        self._next_ticket = 0
        # Per-thread slot inherited from the scheduler (see ``inherit``).
        self._local = threading.local()

    # ------------------------------------------------------------------ public

    @property
    def limit(self) -> int:
        return self._limit

    def inherit(self, token: int | None) -> None:
        """Hand ``token`` to the calling thread's next :meth:`acquire`.

        Used by :class:`~ai_maintainer.scheduler.CycleScheduler`: a worker takes
        the slot, then inherits it into the thread that runs the body, so
        ``run_cycle()``'s own acquire returns the token instead of counting a
        second slot and its release is a no-op (the pool releases it). Passing
        ``None`` clears the inheritance.
        """

        self._local.token = token

    def inherited_token(self) -> int | None:
        """The token inherited by the calling thread, or ``None``."""

        return getattr(self._local, "token", None)

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

        The free-slot test runs *before* the stop poll on purpose: a cycle that
        can be admitted immediately must not consult the stop flag at all, so a
        stop published by a previous cycle cannot turn a later, uncontended
        ``run_cycle()`` into a no-op (the pre-task single-cycle behaviour every
        existing caller relies on).

        A thread that already inherited a token (UTILTOOLS-130 REQ-01) gets it
        back unchanged: the scheduler holds the slot for the whole body.
        """

        level = Difficulty.parse(difficulty)
        if self._limit < 1:  # defensive: config validation already rejects this
            return None
        inherited = self.inherited_token()
        if inherited is not None:
            return inherited
        with self._condition:
            ticket = self._next_ticket
            self._next_ticket += 1
            self._waiters.append((ticket, level, int(cycle), self._clock()))
            try:
                while True:
                    if len(self._active) < self._limit and self._is_head(ticket):
                        self._active[ticket] = int(cycle)
                        self._condition.notify_all()
                        return ticket
                    if should_stop is not None and should_stop():
                        return None
                    self._wait_for_change(SHUTDOWN_POLL_INTERVAL_SEC)
            finally:
                # Deregister on every exit path: admitted, stopped or raised.
                self._waiters = [
                    waiter for waiter in self._waiters if waiter[0] != ticket
                ]
                self._condition.notify_all()

    def release(self, token: int | None) -> None:
        """Free the slot held by ``token``; an unknown token is a no-op.

        An *inherited* token is not released here: the pool that handed it down
        still owns the slot and frees it after the body returned.
        """

        if token is None:
            return
        inherited = self.inherited_token()
        if inherited is not None and int(inherited) == int(token):
            return
        with self._condition:
            self._active.pop(int(token), None)
            self._condition.notify_all()

    def state(self) -> dict[str, Any]:
        """Read-only admission view for ``MaintainerEngine.snapshot()``."""

        with self._condition:
            now = self._clock()
            return {
                "limit": self._limit,
                "active": len(self._active),
                "aging_interval_sec": self._aging_interval(),
                "waiting": [
                    {
                        "cycle": cycle,
                        "difficulty": level.name,
                        "waited_sec": max(0.0, now - enqueued_at),
                        "effective_level": self._effective_level(level, enqueued_at),
                    }
                    for _ticket, level, cycle, enqueued_at in sorted(self._waiters)
                ],
            }

    # ----------------------------------------------------------------- private

    def _aging_interval(self) -> float:
        """Live aging interval in the clock's unit (``0.0`` disables aging)."""

        raw = self._aging_interval_sec
        value = raw() if callable(raw) else raw
        if value is None:
            return 0.0
        try:
            interval = float(value)
        except (TypeError, ValueError):
            return 0.0
        if interval != interval or interval <= 0.0:  # NaN / non-positive
            return 0.0
        return interval

    def _effective_level(self, level: Difficulty, enqueued_at: float) -> int:
        """``difficulty`` plus the REQ-03 aging boost, as a comparable int."""

        base = int(level.value)
        interval = self._aging_interval()
        if interval <= 0.0:
            return base
        waited = max(0.0, self._clock() - enqueued_at)
        return base + int(waited / interval)

    def _is_head(self, ticket: int) -> bool:
        """May ``ticket`` take a free slot right now?

        With priority enforcement on, a waiter yields to a strictly harder one;
        equal difficulty is broken by the lower ticket, i.e. FIFO, so two
        same-difficulty waiters cannot race past each other. UTILTOOLS-130
        REQ-03: the comparison uses the *aged* level, so waiting long enough
        makes a waiter the head regardless of who arrived with which difficulty.
        """

        mine = next(
            (
                (level, enqueued_at)
                for candidate, level, _cycle, enqueued_at in self._waiters
                if candidate == ticket
            ),
            None,
        )
        if mine is None:
            return False
        if not bool(self._enforce_priority()):
            # Operator opt-out: strict FIFO, the lowest live ticket wins.
            return min(candidate for candidate, _l, _c, _e in self._waiters) == ticket
        mine_rank = self._effective_level(*mine)
        for candidate, level, _cycle, enqueued_at in self._waiters:
            if candidate == ticket:
                continue
            rank = self._effective_level(level, enqueued_at)
            if rank > mine_rank or (rank == mine_rank and candidate < ticket):
                return False
        return True

    def _wait_for_change(self, timeout: float) -> None:
        """Block until notified or ``timeout`` elapses.

        The default path parks on the condition. When the engine injected its
        own sleep seam (fake clocks in tests), the seam is used instead, with
        the condition released around it so a ``release()`` is never delayed by
        a sleeping waiter.
        """

        if self._sleep is time.sleep:
            self._condition.wait(timeout)
            return
        self._condition.release()
        try:
            self._sleep(timeout)
        finally:
            self._condition.acquire()


def read_heartbeat(path: Path | None) -> tuple[Path, bytes | None] | None:
    """Capture the raw heartbeat document for a snapshot (never raises).

    Returns ``None`` when no heartbeat path was injected, so a caller that did
    not opt into heartbeat handling gets exactly the pre-task behavior: the
    engine never invents a heartbeat file.

    This is the one read of an operator-writable file, so it carries the guards
    Plan §5 mandates: only a **regular** file is read (a FIFO/socket would block
    the cycle forever), the open is ``O_NOFOLLOW`` (a symlink swap is refused),
    the read is bounded by :data:`HEARTBEAT_READ_LIMIT`, and an oversized
    document is reported as *absent* rather than captured truncated - restoring
    a clipped copy would corrupt the operator's file.
    """

    if path is None:
        return None
    target = Path(path)
    try:
        info = os.lstat(target)
    except OSError:
        return (target, None)
    if not stat.S_ISREG(info.st_mode):
        return (target, None)
    if info.st_size > HEARTBEAT_READ_LIMIT:
        return (target, None)
    try:
        handle_fd = os.open(target, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError:
        return (target, None)
    try:
        with os.fdopen(handle_fd, "rb") as handle:
            data = handle.read(HEARTBEAT_READ_LIMIT)
    except OSError:
        return (target, None)
    return (target, data)


def write_heartbeat(path: Path, data: bytes) -> bool:
    """Restore previously captured heartbeat bytes; ``False`` when it failed.

    The restore half of INV-CONC-5: it never creates a directory (the engine
    must not invent paths), never follows a symlink at either end of the atomic
    ``.tmp`` + ``os.replace`` swap, and leaves the file untouched on failure.
    """

    target = Path(path)
    temporary = target.with_name(target.name + ".tmp")
    try:
        if target.is_symlink():
            return False
        flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
        handle_fd = os.open(temporary, flags, 0o644)
        with os.fdopen(handle_fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        if target.is_symlink():  # narrow the swap window
            raise OSError("heartbeat document is a symlink")
        temporary.replace(target)
    except OSError:
        try:
            temporary.unlink()
        except OSError:
            pass
        return False
    return True


def current_heartbeat(path: Path) -> bytes | None:
    """Current raw bytes of ``path`` (``None`` when absent or unreadable)."""

    captured = read_heartbeat(path)
    return None if captured is None else captured[1]
