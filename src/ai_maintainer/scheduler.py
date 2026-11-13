"""Bounded concurrent cycle scheduler for the maintainer engine (UTILTOOLS-130).

The engine used to run one cycle body at a time: a slow cycle blocked every
later cycle for its whole duration. :class:`CycleScheduler` is the worker pool
that removes that coupling while keeping the bound the operator asked for:

* **REQ-01 - bounded pool with acquire/release semantics.** ``max_workers``
  worker threads own the pool. A worker takes a slot from the injected
  :class:`~ai_maintainer.concurrency.CycleAdmissionGate` *before* it runs a
  body and returns it in a ``finally``, so a body can never leak a slot and at
  most ``gate.limit`` bodies execute at once (INV-SCHED-1). The gate, not the
  pool, stays the single authority on the limit: reducing
  ``max_concurrent_cycles`` at runtime only affects new admissions.
* **REQ-03 - aging.** Fairness across waiting cycles is the gate's job (it
  re-ranks waiters by ``difficulty + floor(waited / aging_interval)``), so a
  cycle that has waited long enough overtakes a newer, harder one.
* **REQ-04 - preemption.** :meth:`CycleScheduler.preempt` picks the
  lowest-priority *running* work whose difficulty is strictly below the
  incoming one and sets its per-work *preemption* event. The body polls
  :meth:`CycleScheduler.preemption_requested` at its own step boundaries
  (INV-SCHED-2: never mid-step), returns, and its worker hands the slot over.
* **REQ-05 - graceful drain.** :meth:`CycleScheduler.drain` stops accepting
  work, signals every in-flight work on the *shutdown* channel
  (:meth:`CycleScheduler.shutdown_requested`), and waits a *bounded* time for
  the pool to go idle (INV-SCHED-3); a timeout is reported, never raised.

The two channels stay distinct on purpose (Review v4 MUST_FIX): a shutdown
drain and a scheduler preemption both ask a body to wind down, but they are
different interruptions - one truncates the cycle for the operator, the other
hands its slot to a higher-priority sibling - and the engine attributes them
separately in the cycle report. Sharing one event made every drained cycle
report ``interrupted_by="PREEMPT"``.

Locking: the scheduler has one :class:`threading.Condition` of its own and the
gate has another. The documented order is scheduler -> gate, and neither is
ever held while the engine's mid-cycle RLock is taken, so a worker that blocks
in the gate cannot deadlock the engine loop.

The module imports only the stdlib plus :class:`Difficulty`, so it cannot form
an import cycle with the engine it serves.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Callable, Deque

from ai_maintainer.tasks.curriculum import Difficulty

__all__ = [
    "CycleScheduler",
    "CycleWork",
    "DEFAULT_SCHEDULER_DRAIN_TIMEOUT_SEC",
    "SCHEDULER_POLL_INTERVAL_SEC",
]

LOG = logging.getLogger(__name__)

#: Wait budget of :meth:`CycleScheduler.drain` when the caller passes none.
DEFAULT_SCHEDULER_DRAIN_TIMEOUT_SEC = 30.0

#: Longest single park of a worker / producer / drainer. Small on purpose: a
#: signal set from another thread must be observed without a long latency, and
#: a ``Condition.wait`` timeout cannot miss a notification (the loop re-tests
#: its predicate after every wake-up).
SCHEDULER_POLL_INTERVAL_SEC = 0.02

#: How many finished work items :meth:`CycleScheduler.state` keeps for the
#: engine report. Bounded so a 24/7 loop cannot grow the scheduler without end.
FINISHED_HISTORY_LIMIT = 32


@dataclass
class CycleWork:
    """One submitted cycle: the scheduling handle the worker threads consume.

    ``cycle`` is the engine cycle id. It is ``None`` until the body reports it
    through :meth:`CycleScheduler.note_cycle` (the engine calls it right after
    it allocates the id), because the id is allocated inside ``run_cycle()`` -
    the public signature of which this task must not change.

    ``stop_event`` and ``preempt_event`` are the two interruption channels of
    this work: ``stop_event`` is set by a shutdown (:meth:`CycleScheduler.drain`
    or :meth:`CycleScheduler.request_stop`), ``preempt_event`` by
    :meth:`CycleScheduler.preempt` when a higher-priority sibling needs the
    slot. A body that sees ``stop_event`` is reporting an operator stop; a body
    that sees only ``preempt_event`` is yielding its slot (REQ-04).
    """

    work_id: int
    difficulty: Difficulty
    body: Callable[[], Any]
    enqueued_at: float
    cycle: int | None = None
    stop_event: threading.Event = field(default_factory=threading.Event)
    preempt_event: threading.Event = field(default_factory=threading.Event)
    started_at: float | None = None
    admitted_at: float | None = None
    finished_at: float | None = None
    token: int | None = None
    admitted: bool = False
    preempted: bool = False
    error: BaseException | None = None

    def describe(self, now: float, *, limit: int) -> dict[str, Any]:
        """Read-only row for the engine's ``scheduler`` report section."""

        started = self.started_at
        finished = self.finished_at
        running_sec = 0.0
        if started is not None:
            running_sec = max(0.0, (finished if finished is not None else now) - started)
        waited_sec = max(0.0, (started if started is not None else now) - self.enqueued_at)
        return {
            "work_id": self.work_id,
            "cycle": self.cycle,
            "difficulty": Difficulty.parse(self.difficulty).name,
            "limit": limit,
            "waited_sec": waited_sec,
            "running_sec": running_sec,
            "preempted": self.preempted,
            "admitted": self.admitted,
            "state": (
                "FINISHED"
                if finished is not None
                else ("RUNNING" if started is not None else "QUEUED")
            ),
        }


class CycleScheduler:
    """Bounded worker pool that runs cycle bodies under the admission gate.

    ``gate`` is the engine's :class:`CycleAdmissionGate` (the slot authority),
    ``max_workers`` is ``MaintainerConfig.max_concurrent_cycles``,
    ``should_stop`` lets the producer and the workers abandon their wait when
    the engine publishes a stop, and ``clock`` is the engine's clock seam so
    the wait/running stamps in the report match the engine's own timestamps.
    """

    def __init__(
        self,
        gate: Any,
        max_workers: int = 2,
        *,
        drain_timeout_sec: float | None = None,
        clock: Callable[[], float] | None = None,
        should_stop: Callable[[], bool] | None = None,
        queue_capacity: int | None = None,
        poll_interval: float = SCHEDULER_POLL_INTERVAL_SEC,
    ) -> None:
        if isinstance(max_workers, bool) or not isinstance(max_workers, int):
            raise ValueError("max_workers must be an int >= 1")
        if max_workers < 1:
            raise ValueError("max_workers must be an int >= 1")
        timeout = (
            DEFAULT_SCHEDULER_DRAIN_TIMEOUT_SEC
            if drain_timeout_sec is None
            else float(drain_timeout_sec)
        )
        self._gate = gate
        self._max_workers = max_workers
        self._drain_timeout_sec = max(0.0, timeout)
        self._clock: Callable[[], float] = clock if clock is not None else time.monotonic
        self._should_stop: Callable[[], bool] = (
            should_stop if should_stop is not None else (lambda: False)
        )
        self._poll_interval = max(0.001, float(poll_interval))
        # Backpressure: the producer blocks once this many works are waiting,
        # so a fast loop (``interval_sec=0``) cannot grow the queue without
        # bound. Default one pending work per worker.
        self._queue_capacity = max(1, int(queue_capacity or max_workers))
        self._condition = threading.Condition()
        self._queue: Deque[CycleWork] = deque()
        self._active: dict[int, CycleWork] = {}
        self._finished: Deque[CycleWork] = deque(maxlen=FINISHED_HISTORY_LIMIT)
        self._workers: list[threading.Thread] = []
        self._local = threading.local()
        self._next_work_id = 0
        self._closed = False
        self._timed_out = False
        self._preemptions: list[dict[str, Any]] = []
        # Preemption records whose incoming work had not reported its cycle id
        # yet, keyed by ``work_id`` (see :meth:`attribute_preemption`).
        self._pending_incoming: dict[int, dict[str, Any]] = {}

    # ------------------------------------------------------------------ public

    @property
    def limit(self) -> int:
        """Concurrency limit the pool works to (the gate's live limit)."""

        return int(getattr(self._gate, "limit", self._max_workers))

    @property
    def max_workers(self) -> int:
        return self._max_workers

    @property
    def drain_timeout_sec(self) -> float:
        return self._drain_timeout_sec

    def acquire(
        self, difficulty: Difficulty, cycle: int, *, should_stop: Callable[[], bool] | None = None
    ) -> int | None:
        """Take a pool slot from the gate; ``None`` when a stop got there first.

        Exposed because the pool's contract is acquire/release (REQ-01): the
        worker threads call it, and so can a caller that wants to hold a slot
        itself. The returned token must be handed back through :meth:`release`.
        """

        return self._gate.acquire(
            Difficulty.parse(difficulty),
            int(cycle),
            should_stop=should_stop if should_stop is not None else self._stop_requested,
        )

    def release(self, token: int | None) -> None:
        """Return a slot acquired from :meth:`acquire` (idempotent)."""

        self._gate.release(token)

    def submit(
        self, cycle: int | None, difficulty: Difficulty, body: Callable[[], Any]
    ) -> CycleWork | None:
        """Enqueue one cycle body and return its handle (``None`` when stopped).

        Blocks while the bounded queue is full, polling ``should_stop``, so the
        producer is throttled instead of piling up unbounded work.
        """

        level = Difficulty.parse(difficulty)
        with self._condition:
            while True:
                if self._closed:
                    return None
                if len(self._queue) < self._queue_capacity:
                    break
                self._condition.wait(self._poll_interval)
            self._next_work_id += 1
            work = CycleWork(
                work_id=self._next_work_id,
                difficulty=level,
                body=body,
                enqueued_at=self._clock(),
                cycle=None if cycle is None else int(cycle),
            )
            self._queue.append(work)
            self._ensure_workers_locked()
            self._condition.notify_all()
            return work

    def preempt(
        self, incoming_priority: Difficulty, incoming_cycle: int | None = None
    ) -> int | None:
        """Signal the lowest-priority running work to wind down (REQ-04).

        Returns the preempted cycle id (or the scheduler ``work_id`` when the
        cycle had not reported its id yet), or ``None`` when nothing was
        preempted - a slot is free, no work is *running* yet, or no running work
        is strictly below ``incoming_priority``.

        ``incoming_cycle`` is the id of the cycle the caller wants the slot for;
        it is stamped into the preemption record. When the producer does not
        know the id yet (the engine allocates it inside ``run_cycle()``) it can
        link the work afterwards through :meth:`attribute_preemption`.

        Review v4 NIT: the *capacity* test asks the gate for its live active
        count instead of comparing the pool's own running count to the limit, so
        ``cycle_pool_size < max_concurrent_cycles`` (e.g. ``1`` slot for a
        3-worker pool) still preempts: the gate is saturated, which is what
        "the pool has no free slot" actually means.
        """

        level = Difficulty.parse(incoming_priority)
        # Read the gate's capacity *outside* the pool lock (the gate has its own
        # lock; the pool never nests the two).
        gate_active = self._gate_active()
        with self._condition:
            if not self._active or gate_active < self.limit:
                # A free slot exists: nobody has to be interrupted.
                return None
            candidates = [
                work
                for work in self._active.values()
                if work.admitted
                and not work.preempted
                and not work.stop_event.is_set()
                and Difficulty.parse(work.difficulty) < level
            ]
            if not candidates:
                return None
            victim = min(
                candidates,
                key=lambda work: (
                    Difficulty.parse(work.difficulty).value,
                    # Same difficulty: the work that has run longest yields
                    # first, so the *newest* lower-priority cycle is the least
                    # likely to lose progress.
                    -(work.started_at or 0.0),
                    work.work_id,
                ),
            )
            victim.preempted = True
            # Preemption channel, *not* the shutdown one: the engine attributes
            # the interrupted cycle from which channel fired (Review v4).
            victim.preempt_event.set()
            record = {
                "cycle": victim.cycle,
                "work_id": victim.work_id,
                "difficulty": Difficulty.parse(victim.difficulty).name,
                "incoming_cycle": None if incoming_cycle is None else int(incoming_cycle),
                "incoming_work_id": None,
                "incoming_difficulty": level.name,
            }
            self._preemptions.append(record)
            self._condition.notify_all()
            LOG.info(
                "scheduler preempting cycle=%s difficulty=%s for incoming cycle=%s difficulty=%s",
                victim.cycle,
                Difficulty.parse(victim.difficulty).name,
                incoming_cycle,
                level.name,
            )
            return victim.cycle if victim.cycle is not None else victim.work_id

    def attribute_preemption(
        self, incoming_work: CycleWork | None, victim: int | None = None
    ) -> None:
        """Link the work that took the preempted slot to its record (REQ-04).

        The engine calls this right after admitting the incoming cycle: the
        cycle id only exists once the body is running, so the record is stamped
        here when it is already known and filled by :meth:`note_cycle`
        otherwise. This is what keeps ``incoming_cycle`` from being permanently
        ``None`` in the cycle report (Review v4 NIT).
        """

        if incoming_work is None:
            return
        with self._condition:
            record = next(
                (
                    candidate
                    for candidate in reversed(self._preemptions)
                    if candidate.get("incoming_work_id") is None
                    and candidate.get("incoming_cycle") is None
                    and (victim is None or candidate.get("cycle") == victim
                         or candidate.get("work_id") == victim)
                ),
                None,
            )
            if record is None:
                return
            record["incoming_work_id"] = incoming_work.work_id
            if incoming_work.cycle is not None:
                record["incoming_cycle"] = int(incoming_work.cycle)
            else:
                self._pending_incoming[incoming_work.work_id] = record
            self._condition.notify_all()

    def note_cycle(self, cycle: int) -> None:
        """Record the engine cycle id of the work this thread is running.

        Also completes a pending preemption record when this work is the one
        that took the preempted slot.
        """

        work = getattr(self._local, "work", None)
        if work is None:
            return
        record = self._pending_incoming.pop(work.work_id, None)
        if record is not None and record.get("incoming_cycle") is None:
            record["incoming_cycle"] = int(cycle)
        if work.cycle is None:
            work.cycle = int(cycle)

    def current_work(self) -> CycleWork | None:
        """The work the calling thread is running (``None`` off-pool)."""

        return getattr(self._local, "work", None)

    def preemption_requested(self) -> bool:
        """Has *this thread's* work been asked to yield its slot (REQ-04)?

        The engine's step loop calls this at its break points. Off-pool (a
        direct ``run_cycle()`` caller) it is always ``False``, which is what
        keeps the historical single-cycle call shape unchanged.

        Only the *preemption* channel is reported here; a shutdown signal is
        :meth:`shutdown_requested` (Review v4 MUST_FIX - the two interruptions
        are attributed separately in the cycle report).
        """

        work = getattr(self._local, "work", None)
        return work is not None and work.preempt_event.is_set()

    def shutdown_requested(self) -> bool:
        """Has *this thread's* work been told to wind down by a drain (REQ-05)?

        ``True`` once :meth:`drain` / :meth:`request_stop` signalled this work.
        Off-pool it is always ``False``. Kept separate from
        :meth:`preemption_requested` so the engine can tell an operator stop
        (``interrupted_by="STOP"``) from a slot hand-over (``"PREEMPT"``).
        """

        work = getattr(self._local, "work", None)
        return work is not None and work.stop_event.is_set()

    def drain(self, timeout_sec: float | None = None) -> bool:
        """Stop accepting work and wait for the pool to go idle (REQ-05).

        Every queued work is abandoned and every running work is signalled on
        the *shutdown* channel (:meth:`shutdown_requested`), never on the
        preemption one; the wait is bounded by ``timeout_sec`` (default
        ``drain_timeout_sec``) and by the wall clock, so a stuck body can never
        park the wind-down. Returns ``True`` when the pool drained cleanly and
        ``False`` on timeout, in which case the still-active cycle ids are
        logged (INV-SCHED-3).
        """

        timeout = self._drain_timeout_sec if timeout_sec is None else float(timeout_sec)
        timeout = max(0.0, timeout)
        # Deadline on the injected clock seam (REQ-05): a fake clock can bound
        # the drain wait just like it bounds the engine's own waits.
        deadline = self._clock() + timeout
        with self._condition:
            self._closed = True
            abandoned = list(self._queue)
            self._queue.clear()
            now = self._clock()
            for work in abandoned:
                work.finished_at = now
                self._finished.append(work)
            for work in self._active.values():
                work.stop_event.set()
            self._condition.notify_all()
            while self._active:
                remaining = deadline - self._clock()
                if remaining <= 0.0:
                    self._timed_out = True
                    stuck = sorted(
                        str(work.cycle if work.cycle is not None else work.work_id)
                        for work in self._active.values()
                    )
                    LOG.warning(
                        "scheduler drain timed out after %.2fs; active cycles=%s",
                        timeout,
                        ",".join(stuck) or "none",
                    )
                    break
                self._condition.wait(min(remaining, self._poll_interval))
        self._join_workers()
        return not self._timed_out

    def request_stop(self) -> None:
        """Signal every in-flight work on the shutdown channel, without waiting.

        Same channel as :meth:`drain`, so the engine attributes the truncated
        cycle to the operator stop rather than to a preemption (REQ-05).
        """

        with self._condition:
            self._closed = True
            for work in self._active.values():
                work.stop_event.set()
            self._condition.notify_all()

    def wait_idle(
        self,
        timeout_sec: float | None = None,
        *,
        should_stop: Callable[[], bool] | None = None,
    ) -> bool:
        """Block until nothing is queued and nothing is running (REQ-02).

        The producer paces itself on *completions* through this call instead
        of a sleep loop, so the engine's pacing seam stays reserved for
        ``_pause()`` - a fake-clock test still counts only real pauses.
        Returns ``True`` when the pool is idle and ``False`` on timeout or on
        a stop request (whichever came first).
        """

        deadline = (
            None
            if timeout_sec is None
            else self._clock() + max(0.0, float(timeout_sec))
        )
        with self._condition:
            while True:
                if not self._queue and not self._active:
                    return True
                if self._stop_requested():
                    return False
                if should_stop is not None and should_stop():
                    return False
                if deadline is None:
                    wait_for = self._poll_interval
                else:
                    remaining = deadline - self._clock()
                    if remaining <= 0.0:
                        return False
                    wait_for = min(remaining, self._poll_interval)
                self._condition.wait(wait_for)

    def state(self) -> dict[str, Any]:
        """Read-only scheduler view for :meth:`MaintainerEngine.snapshot`."""

        with self._condition:
            now = self._clock()
            limit = self.limit
            active = [
                work.describe(now, limit=limit)
                for work in sorted(self._active.values(), key=lambda item: item.work_id)
            ]
            queued = [
                work.describe(now, limit=limit)
                for work in sorted(self._queue, key=lambda item: item.work_id)
            ]
            oldest_wait = max(
                (row["waited_sec"] for row in active + queued), default=0.0
            )
            finished = [
                work.describe(now, limit=limit)
                for work in list(self._finished)[-FINISHED_HISTORY_LIMIT:]
            ]
            return {
                "limit": limit,
                "max_workers": self._max_workers,
                "queue_capacity": self._queue_capacity,
                "active": len(active),
                "queued": len(queued),
                "idle_workers": max(0, self._max_workers - len(active)),
                "oldest_wait_sec": oldest_wait,
                "closed": self._closed,
                "timed_out": self._timed_out,
                "drain_timeout_sec": self._drain_timeout_sec,
                "preemptions": len(self._preemptions),
                "last_preemption": (
                    dict(self._preemptions[-1]) if self._preemptions else None
                ),
                "active_work": active,
                "queued_work": queued,
                "finished_work": finished,
            }

    # ----------------------------------------------------------------- private

    def _stop_requested(self) -> bool:
        if self._closed:
            return True
        try:
            return bool(self._should_stop())
        except Exception:  # pragma: no cover - defensive
            return True

    def _gate_active(self) -> int:
        """Live slot count held in the admission gate (the capacity authority).

        Falls back to the pool's own running count for a gate without a
        ``state()`` view, so the class stays usable with a minimal stand-in.
        """

        view = getattr(self._gate, "state", None)
        if callable(view):
            try:
                return int(view().get("active", len(self._active)))
            except Exception:  # pragma: no cover - defensive
                pass
        return len(self._active)

    def _ensure_workers_locked(self) -> None:
        while len(self._workers) < self._max_workers:
            worker = threading.Thread(
                target=self._worker_loop,
                name=f"cycle-scheduler-{len(self._workers) + 1}",
                daemon=True,
            )
            self._workers.append(worker)
            worker.start()

    def _join_workers(self) -> None:
        for worker in list(self._workers):
            worker.join(timeout=max(self._drain_timeout_sec, 0.0))

    def _worker_loop(self) -> None:
        while True:
            with self._condition:
                while not self._queue and not self._closed:
                    self._condition.wait(self._poll_interval)
                if not self._queue:
                    # Closed and nothing left: the worker retires.
                    return
                work = self._queue.popleft()
                work.started_at = self._clock()
                self._active[work.work_id] = work
                self._condition.notify_all()
            token: int | None = None
            try:
                token = self._gate.acquire(
                    Difficulty.parse(work.difficulty),
                    work.work_id,
                    should_stop=self._stop_requested,
                )
                if token is None:
                    # Stop published while queued: the work never ran.
                    continue
                work.token = token
                work.admitted = True
                work.admitted_at = self._clock()
                self._local.work = work
                # Hand the token down to ``run_cycle()``: its own gate acquire
                # returns this token and its release is a no-op, so the slot is
                # counted exactly once and released by this worker.
                self._gate.inherit(token)
                work.body()
            except BaseException as exc:  # pragma: no cover - defensive
                work.error = exc
                LOG.warning(
                    "scheduler cycle work %d raised: %r", work.work_id, exc
                )
            finally:
                self._local.work = None
                self._gate.inherit(None)
                try:
                    self._gate.release(token)
                finally:
                    with self._condition:
                        self._active.pop(work.work_id, None)
                        work.finished_at = self._clock()
                        self._finished.append(work)
                        self._condition.notify_all()
