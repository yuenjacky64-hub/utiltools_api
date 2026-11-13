"""Tests for the bounded concurrent cycle scheduler (UTILTOOLS-130).

Covers the DoD rows of the task brief:

* (a) the pool never exceeds ``max_concurrent_cycles`` (REQ-01);
* (b) a long-waiting low-difficulty cycle eventually overtakes a newer,
  harder one through the gate's aging boost (REQ-03);
* (c) preemption lets a high-priority cycle take a slot away from a running
  low-priority one (REQ-04);
* (d) drain finalises in-flight work on shutdown and a stopped scheduler
  refuses new work (REQ-05);
* the scheduler view that rides along in ``MaintainerEngine.snapshot()``
  describes the pool (REQ-02).

Everything here is stdlib + the project's own modules: no engine, no JVM, no
network, so the file stays fast enough for the default suite.
"""

from __future__ import annotations

import threading
import time

import pytest

from ai_maintainer.concurrency import CycleAdmissionGate
from ai_maintainer.scheduler import (
    SCHEDULER_POLL_INTERVAL_SEC,
    CycleScheduler,
    CycleWork,
)
from ai_maintainer.tasks.curriculum import Difficulty


# --------------------------------------------------------------------- helpers


class FakeClock:
    """Manually advanced clock; the gate/scheduler only ever call it."""

    def __init__(self, start: float = 0.0) -> None:
        self.now = float(start)
        self._lock = threading.Lock()

    def __call__(self) -> float:
        with self._lock:
            return self.now

    def advance(self, delta: float) -> None:
        with self._lock:
            self.now += float(delta)


def wait_until(predicate, timeout: float = 5.0, interval: float = 0.01) -> bool:
    """Poll ``predicate`` until true; return whether it became true in time."""

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return bool(predicate())


def make_gate(limit: int = 2, **kwargs) -> CycleAdmissionGate:
    # ``sleep`` is left at the real ``time.sleep``: the gate parks a waiter for
    # at most ~20ms per wake-up, which keeps the tests responsive.
    return CycleAdmissionGate(limit, **kwargs)


def make_scheduler(gate: CycleAdmissionGate, max_workers: int = 2, **kwargs) -> CycleScheduler:
    kwargs.setdefault("poll_interval", SCHEDULER_POLL_INTERVAL_SEC)
    kwargs.setdefault("drain_timeout_sec", 5.0)
    return CycleScheduler(gate, max_workers, **kwargs)


# ------------------------------------------------------------ pool bounding


def test_scheduler_enforces_max_concurrent():
    """At most ``limit`` bodies run at the same time (REQ-01, DoD a)."""

    gate = make_gate(limit=2)
    scheduler = make_scheduler(gate, max_workers=2)
    lock = threading.Lock()
    running = 0
    peak = 0
    done = []

    def body() -> None:
        nonlocal running, peak
        with lock:
            running += 1
            peak = max(peak, running)
        time.sleep(0.05)
        with lock:
            running -= 1
            done.append(threading.current_thread().name)

    try:
        for cycle in range(6):
            assert scheduler.submit(cycle, Difficulty.EASY, body) is not None
        assert wait_until(lambda: len(done) == 6)
    finally:
        assert scheduler.drain(timeout_sec=5.0) is True

    assert peak == 2, f"pool admitted {peak} bodies concurrently, limit is 2"
    assert len(done) == 6
    assert scheduler.state()["active"] == 0


def test_acquire_release_are_the_public_slot_contract():
    """A slot taken with ``acquire`` is returned by ``release`` (REQ-01)."""

    gate = make_gate(limit=1)
    scheduler = make_scheduler(gate, max_workers=1)

    token = scheduler.acquire(Difficulty.HARD, 7)
    assert token is not None
    assert scheduler.state()["limit"] == 1

    # The single slot is taken: a second acquire must not succeed eagerly.
    blocked: list[int | None] = []

    def contender() -> None:
        blocked.append(scheduler.acquire(Difficulty.EASY, 8))

    thread = threading.Thread(target=contender, daemon=True)
    thread.start()
    time.sleep(0.1)
    assert blocked == []

    scheduler.release(token)
    # Idempotent release must not raise or double-free the slot.
    scheduler.release(token)
    scheduler.release(None)

    assert wait_until(lambda: bool(blocked), timeout=3.0)
    scheduler.release(blocked[0])

    assert scheduler.drain(timeout_sec=5.0) is True


def test_gate_limit_is_the_single_authority():
    """Lowering ``limit`` at runtime is observed by the scheduler (REQ-01)."""

    gate = make_gate(limit=3)
    scheduler = make_scheduler(gate, max_workers=3)
    assert scheduler.limit == 3

    gate._limit = 1  # documented runtime knob: only affects new admissions
    assert scheduler.limit == 1
    assert scheduler.state()["max_workers"] == 3


# ------------------------------------------------------------------- aging


def test_aging_overtakes_newer_higher_difficulty():
    """A cycle that has waited long enough outranks a fresh, harder arrival (REQ-03).

    Without aging the gate is a strict priority queue, so a COMPLEX cycle that
    arrives at the last moment always beats an EASY one that has been queued
    for ages - that is the starvation the brief forbids. Aging adds
    ``waited / aging_interval_sec`` to the effective level, so the long waiter
    wins once it has aged past the difficulty gap.
    """

    clock = FakeClock()
    gate = make_gate(limit=1, aging_interval_sec=10.0, clock=clock)
    scheduler = make_scheduler(gate, max_workers=1, clock=clock)

    order: list[int] = []
    holder = gate.acquire(Difficulty.HARD, 1)
    assert holder is not None

    def waiter(cycle: int, level: Difficulty) -> None:
        token = gate.acquire(level, cycle)
        if token is not None:
            order.append(cycle)
            gate.release(token)

    # The easy cycle takes its place in the queue at t=0 ...
    easy = threading.Thread(target=waiter, args=(2, Difficulty.EASY), daemon=True)
    easy.start()
    assert wait_until(lambda: [row["cycle"] for row in gate.state()["waiting"]] == [2])

    # ... and ages for 100s while the single slot stays taken.
    clock.advance(100.0)
    waiting = gate.state()["waiting"]
    assert waiting[0]["waited_sec"] == pytest.approx(100.0)
    assert waiting[0]["effective_level"] == 10  # EASY(0) + 100/10

    # A COMPLEX cycle arrives now: base 3 against the aged 10.
    hard = threading.Thread(target=waiter, args=(3, Difficulty.COMPLEX), daemon=True)
    hard.start()
    assert wait_until(lambda: len(gate.state()["waiting"]) == 2)
    levels = {
        row["cycle"]: row["effective_level"] for row in gate.state()["waiting"]
    }
    assert levels == {2: 10, 3: Difficulty.COMPLEX}, levels

    gate.release(holder)
    # Both waiters are served (nothing starves) and the aged EASY cycle is
    # admitted first, ahead of the fresh COMPLEX one.
    assert wait_until(lambda: len(order) == 2, timeout=3.0), order
    assert order == [2, 3], "the aged cycle did not overtake the fresh harder one"
    assert scheduler.drain(timeout_sec=5.0) is True


def test_aging_disabled_falls_back_to_static_priority():
    """``aging_interval_sec=0`` keeps the plain difficulty ordering (REQ-03)."""

    clock = FakeClock()
    gate = make_gate(limit=1, aging_interval_sec=0.0, clock=clock)
    assert gate._effective_level(Difficulty.EASY, clock.now - 1000.0) == Difficulty.EASY
    assert gate._effective_level(Difficulty.COMPLEX, clock.now) == Difficulty.COMPLEX


# --------------------------------------------------------------- preemption


def test_preemption_reduces_wait():
    """A high-priority cycle can evict a running low-priority one (REQ-04)."""

    gate = make_gate(limit=1)
    scheduler = make_scheduler(gate, max_workers=1)
    started = threading.Event()
    observed = threading.Event()
    finished: list[str] = []

    def low_body() -> None:
        scheduler.note_cycle(11)
        started.set()
        # The body polls at its own step boundary (INV-SCHED-2).
        for _ in range(500):
            if scheduler.preemption_requested():
                observed.set()
                finished.append("preempted")
                return
            time.sleep(0.01)
        finished.append("completed")

    try:
        assert scheduler.submit(11, Difficulty.EASY, low_body) is not None
        assert started.wait(5.0)

        # Pool is full (limit 1) and the running work is strictly easier.
        victim = scheduler.preempt(Difficulty.COMPLEX, incoming_cycle=12)
        assert victim == 11
        assert observed.wait(5.0)
        assert wait_until(lambda: finished == ["preempted"])

        view = scheduler.state()
        assert view["preemptions"] == 1
        assert view["last_preemption"]["cycle"] == 11
        assert view["last_preemption"]["incoming_difficulty"] == "COMPLEX"

        # REQ-04 payoff: the evicted slot is free again immediately, so the
        # COMPLEX cycle is admitted in well under the victim's own run time
        # (the EASY body would have polled for ~5s).
        admitted = threading.Event()
        t0 = time.monotonic()

        def high_body() -> None:
            scheduler.note_cycle(12)
            admitted.set()

        assert scheduler.submit(12, Difficulty.COMPLEX, high_body) is not None
        assert admitted.wait(5.0)
        assert time.monotonic() - t0 < 2.0
    finally:
        assert scheduler.drain(timeout_sec=5.0) is True


def test_preempt_is_a_no_op_without_a_running_lower_priority_cycle():
    """Preemption never fires into a free pool or against an equal priority (REQ-04)."""

    gate = make_gate(limit=2)
    scheduler = make_scheduler(gate, max_workers=2)
    try:
        # Nothing running yet: a free slot exists.
        assert scheduler.preempt(Difficulty.COMPLEX, incoming_cycle=1) is None

        running = threading.Event()
        release = threading.Event()

        def body() -> None:
            scheduler.note_cycle(21)
            running.set()
            release.wait(5.0)

        assert scheduler.submit(21, Difficulty.COMPLEX, body) is not None
        assert running.wait(5.0)

        # Pool limit is 2 and only one slot is used -> nobody is interrupted.
        assert scheduler.preempt(Difficulty.COMPLEX, incoming_cycle=22) is None
        release.set()
        assert wait_until(lambda: scheduler.state()["active"] == 0)
        assert scheduler.state()["preemptions"] == 0
    finally:
        assert scheduler.drain(timeout_sec=5.0) is True


def test_preemption_request_is_per_thread():
    """Off-pool callers are never asked to preempt (REQ-04/back-compat)."""

    gate = make_gate(limit=1)
    scheduler = make_scheduler(gate, max_workers=1)
    observed: list[bool] = []

    def body() -> None:
        observed.append(scheduler.preemption_requested())
        observed.append(scheduler.current_work() is not None)

    try:
        assert scheduler.submit(31, Difficulty.EASY, body) is not None
        assert wait_until(lambda: len(observed) == 2)
        assert observed == [False, True]
        # Main thread, not a worker: no work, no preemption request.
        assert scheduler.preemption_requested() is False
        assert scheduler.current_work() is None
    finally:
        assert scheduler.drain(timeout_sec=5.0) is True


# ----------------------------------------------------------------- shutdown


def test_graceful_drain_on_shutdown():
    """Shutdown stops running bodies and rejects later submissions (REQ-05)."""

    gate = make_gate(limit=2)
    scheduler = make_scheduler(gate, max_workers=2, drain_timeout_sec=5.0)
    started = threading.Event()
    stopped = threading.Event()

    def body() -> None:
        scheduler.note_cycle(41)
        started.set()
        for _ in range(500):
            if scheduler.preemption_requested():
                stopped.set()
                return
            time.sleep(0.01)

    assert scheduler.submit(41, Difficulty.MEDIUM, body) is not None
    assert started.wait(5.0)

    assert scheduler.drain(timeout_sec=5.0) is True
    assert stopped.is_set(), "drain left an in-flight body running"
    assert scheduler.state()["active"] == 0
    assert scheduler.state()["closed"] is True
    assert scheduler.state()["timed_out"] is False
    # A closed pool refuses further work instead of stranding it.
    assert scheduler.submit(42, Difficulty.EASY, lambda: None) is None


def test_drain_abandons_queued_work():
    """Queued work is finalised (not lost) when the pool drains (REQ-05)."""

    gate = make_gate(limit=1)
    scheduler = make_scheduler(gate, max_workers=1)
    holder = threading.Event()
    ran: list[int] = []

    def body() -> None:
        holder.set()
        time.sleep(0.05)

    try:
        assert scheduler.submit(51, Difficulty.HARD, body) is not None
        assert holder.wait(5.0)
        # Second work waits behind the first; queue capacity is 1 by default.
        queued = scheduler.submit(52, Difficulty.EASY, lambda: ran.append(52))
        assert isinstance(queued, CycleWork)
        drained = scheduler.drain(timeout_sec=5.0)
        assert drained is True
        assert ran == [], "a queued body ran after drain"
        assert scheduler.state()["active"] == 0
    finally:
        scheduler.request_stop()


def test_request_stop_signals_without_waiting():
    """``request_stop`` releases running work without blocking (REQ-05)."""

    gate = make_gate(limit=1)
    scheduler = make_scheduler(gate, max_workers=1)
    started = threading.Event()

    def body() -> None:
        scheduler.note_cycle(61)
        started.set()
        while not scheduler.preemption_requested():
            time.sleep(0.01)

    try:
        assert scheduler.submit(61, Difficulty.HARD, body) is not None
        assert started.wait(5.0)
        scheduler.request_stop()
        assert wait_until(lambda: scheduler.state()["active"] == 0, timeout=5.0)
    finally:
        scheduler.drain(timeout_sec=5.0)


def test_separate_pools_do_not_share_slots():
    """Two schedulers keep independent views over their own gates (REQ-01)."""

    first = make_scheduler(make_gate(limit=1), max_workers=1)
    second = make_scheduler(make_gate(limit=3), max_workers=3)
    try:
        assert first.state()["limit"] == 1
        assert second.state()["limit"] == 3
        assert first.state()["active"] == 0 and second.state()["active"] == 0
    finally:
        assert first.drain(timeout_sec=5.0) is True
        assert second.drain(timeout_sec=5.0) is True


# ------------------------------------------------------------- engine report


def test_state_view_describes_the_pool():
    """The dict the engine reports has the documented shape (REQ-02)."""

    gate = make_gate(limit=2)
    scheduler = make_scheduler(gate, max_workers=2)
    try:
        assert scheduler.submit(71, Difficulty.MEDIUM, lambda: None) is not None
        assert wait_until(
            lambda: any(
                row["cycle"] == 71 for row in scheduler.state()["finished_work"]
            )
        )

        view = scheduler.state()
        for key in (
            "limit",
            "max_workers",
            "queue_capacity",
            "active",
            "queued",
            "idle_workers",
            "oldest_wait_sec",
            "closed",
            "timed_out",
            "drain_timeout_sec",
            "preemptions",
            "last_preemption",
            "active_work",
            "queued_work",
            "finished_work",
        ):
            assert key in view, key

        finished = view["finished_work"]
        assert any(row["cycle"] == 71 for row in finished), finished
        row = next(row for row in finished if row["cycle"] == 71)
        assert row["state"] == "FINISHED"
        assert row["difficulty"] == "MEDIUM"
        assert row["limit"] == 2
        assert row["admitted"] is True
    finally:
        assert scheduler.drain(timeout_sec=5.0) is True


def test_validates_worker_count():
    """A pool without workers is a configuration error, not a silent hang."""

    gate = make_gate(limit=1)
    with pytest.raises(ValueError):
        CycleScheduler(gate, 0)
    with pytest.raises(ValueError):
        CycleScheduler(gate, True)
