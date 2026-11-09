"""Tests for concurrent cycle execution support (UTILTOOLS-77).

Covers the new concurrency seams (plan DOD-01..04, REQ-01..05):

* :class:`CycleAdmissionGate` bounds concurrency to ``max_concurrent_cycles``
  and admits the highest-difficulty queued cycle first (REQ-01, REQ-03).
* Per-cycle snapshot isolation at the :class:`MemoryStore` level:
  ``snapshot_records`` / ``drop_records(cycle=...)`` / ``restore_records``
  so a failed overlapping cycle reverts to its own baseline and trusted
  records from *other* cycles survive (REQ-02, REQ-05).
* Engine wiring: the gate is actually consulted in ``run_cycle`` (no slot
  leak), ``request_stop`` skips a queued cycle, and overlapping idle cycles
  attribute watcher state to their own cycle id without corrupting the
  shared store (REQ-01, REQ-04, REQ-05).
* The atomic heartbeat seam (REQ-02).

These tests are scoped to the admission gate, store primitives and
engine wiring; they do not re-test the engine's existing task pipeline.
"""

from __future__ import annotations

import threading
import time
from pathlib import Path

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.concurrency import (
    CycleAdmissionGate,
    current_heartbeat,
    read_heartbeat,
    write_heartbeat,
)
from ai_maintainer.engine import MaintainerEngine
from ai_maintainer.memory.store import MemoryStore
from ai_maintainer.tasks.curriculum import Difficulty, TaskCurriculum


class FakeWatcher:
    """Stand-in ``MemoryWatcher`` exposing only ``active_task_ids``."""

    def __init__(self, active_task_ids: tuple[str, ...]) -> None:
        self.active_task_ids = tuple(active_task_ids)


def _make_engine(
    tmp_path: Path,
    *,
    max_cycles: int = 2,
    priority: bool = True,
    memory: MemoryStore | None = None,
    watcher: FakeWatcher | None = None,
    heartbeat: str | Path | None = None,
) -> MaintainerEngine:
    """Build a lightweight engine for the admission / isolation seams.

    An empty curriculum and a no-op step keep ``run_cycle`` fast and idle
    (it breaks on the first step when nothing is enqueued), which is exactly
    what these concurrency tests need.
    """
    config = MaintainerConfig(
        max_concurrent_cycles=max_cycles,
        priority_preemption_enabled=priority,
    )
    curriculum = TaskCurriculum(specs=[])
    return MaintainerEngine(
        curriculum,
        config,
        memory=memory,
        watcher=watcher,
        heartbeat_path=heartbeat,
    )


# ---------------------------------------------------------------------------
# REQ-01 / DOD-01 — the gate bounds concurrency to the configured limit
# ---------------------------------------------------------------------------


def test_gate_bounds_concurrency() -> None:
    """At most ``limit`` cycles may hold the gate at once; the rest queue."""
    gate = CycleAdmissionGate(2)
    stop = threading.Event()

    inside = 0
    inside_lock = threading.Lock()
    max_inside = 0

    def worker(cycle: int) -> None:
        nonlocal inside, max_inside
        token = gate.acquire(Difficulty.EASY, cycle, should_stop=stop.is_set)
        assert token is not None, "a cycle must eventually be admitted"
        with inside_lock:
            inside += 1
            max_inside = max(max_inside, inside)
        time.sleep(0.03)  # hold the slot so the others overlap
        with inside_lock:
            inside -= 1
        gate.release(token)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(1, 4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=5.0)

    assert not any(t.is_alive() for t in threads), "a cycle deadlocked on the gate"
    assert max_inside == 2, f"gate over-admitted: {max_inside} cycles inside a limit-2 gate"


# ---------------------------------------------------------------------------
# REQ-03 / DOD-03 — priority-ordered admission of a contested slot
# ---------------------------------------------------------------------------


def test_gate_priority_orders_contested_slot() -> None:
    """With one free slot, a queued COMPLEX cycle is admitted before EASY."""
    gate = CycleAdmissionGate(1)
    stop = threading.Event()

    holder = gate.acquire(Difficulty.EASY, 99, should_stop=stop.is_set)
    assert holder is not None, "the occupying slot must admit immediately"

    order: list[Difficulty] = []
    order_lock = threading.Lock()
    complex_admitted = threading.Event()
    easy_admitted = threading.Event()
    release_complex = threading.Event()

    def complex_worker() -> None:
        token = gate.acquire(Difficulty.COMPLEX, 3, should_stop=stop.is_set)
        assert token is not None
        with order_lock:
            order.append(Difficulty.COMPLEX)
        complex_admitted.set()
        release_complex.wait(timeout=5.0)
        gate.release(token)

    def easy_worker() -> None:
        token = gate.acquire(Difficulty.EASY, 2, should_stop=stop.is_set)
        assert token is not None
        with order_lock:
            order.append(Difficulty.EASY)
        easy_admitted.set()
        gate.release(token)

    t_complex = threading.Thread(target=complex_worker)
    t_easy = threading.Thread(target=easy_worker)
    t_complex.start()
    t_easy.start()
    time.sleep(0.1)  # let both waiters register behind the occupying slot

    assert not complex_admitted.is_set() and not easy_admitted.is_set(), (
        "the contested slot must stay full until the holder releases it"
    )

    gate.release(holder)  # free the single slot

    assert complex_admitted.wait(timeout=5.0), "COMPLEX must take the contested slot first"
    assert not easy_admitted.is_set(), "EASY must stay queued behind COMPLEX"

    release_complex.set()
    assert easy_admitted.wait(timeout=5.0), "EASY must be admitted after COMPLEX releases"

    assert order == [Difficulty.COMPLEX, Difficulty.EASY]


def test_gate_fifo_when_priority_disabled() -> None:
    """With priority preemption off, admission is strict FIFO (first-come)."""
    gate = CycleAdmissionGate(1, enforce_priority=lambda: False)
    stop = threading.Event()

    holder = gate.acquire(Difficulty.EASY, 99, should_stop=stop.is_set)
    assert holder is not None

    order: list[Difficulty] = []
    order_lock = threading.Lock()
    first_admitted = threading.Event()
    release_first = threading.Event()

    def first() -> None:  # the earlier-enqueued EASY cycle
        token = gate.acquire(Difficulty.EASY, 1, should_stop=stop.is_set)
        assert token is not None
        with order_lock:
            order.append(Difficulty.EASY)
        first_admitted.set()
        release_first.wait(timeout=5.0)
        gate.release(token)

    def second() -> None:  # a later-enqueued, higher-difficulty COMPLEX cycle
        token = gate.acquire(Difficulty.COMPLEX, 2, should_stop=stop.is_set)
        assert token is not None
        with order_lock:
            order.append(Difficulty.COMPLEX)
        gate.release(token)

    t1 = threading.Thread(target=first)
    t2 = threading.Thread(target=second)
    t1.start()
    time.sleep(0.05)
    t2.start()
    time.sleep(0.05)

    gate.release(holder)
    assert first_admitted.wait(timeout=5.0)
    release_first.set()
    t1.join(timeout=5.0)
    t2.join(timeout=5.0)

    assert order == [Difficulty.EASY, Difficulty.COMPLEX], (
        "with priority off the earlier-enqueued cycle wins even though it is easier"
    )


# ---------------------------------------------------------------------------
# REQ-04 / DOD-04 — a stopped gate releases its waiters instead of hanging
# ---------------------------------------------------------------------------


def test_gate_stop_releases_waiters() -> None:
    """A queued cycle observing the stop flag leaves the gate without hanging."""
    gate = CycleAdmissionGate(1)
    stop = threading.Event()

    holder = gate.acquire(Difficulty.EASY, 99, should_stop=lambda: False)
    assert holder is not None

    result: dict[str, object] = {}

    def waiting_cycle() -> None:
        result["token"] = gate.acquire(Difficulty.COMPLEX, 2, should_stop=stop.is_set)

    t = threading.Thread(target=waiting_cycle)
    t.start()
    time.sleep(0.05)  # let the cycle register as a waiter
    stop.set()
    t.join(timeout=5.0)

    assert not t.is_alive(), "a stopped cycle must not remain blocked on the gate"
    assert result.get("token") is None, "a cycle that observes stop is skipped, not admitted"
    gate.release(holder)


# ---------------------------------------------------------------------------
# REQ-02 / REQ-05 — per-cycle snapshot isolation at the MemoryStore level
# ---------------------------------------------------------------------------


def test_snapshot_records_isolation(tmp_path: Path) -> None:
    """A peer cycle's record survives one cycle's failure teardown, and the
    snapshot restores the pre-failure store state."""
    store = MemoryStore(tmp_path / "memory.json")
    store.add_learning({"id": "A"}, cycle=1)
    baseline = store.snapshot_records()  # captures just A (cycle 1)

    # An overlapping cycle drops its own record and commits its own.
    store.add_learning({"id": "B"}, cycle=2)

    # Simulating cycle 1's failure teardown: drop only its own records.
    removed = store.drop_records(cycle=1)
    assert removed == 1
    docs = store.records()["learnings"]
    assert [r.get("id") for r in docs] == ["B"], (
        "the peer cycle's record B must survive cycle 1's teardown"
    )

    # Restore cycle 1's snapshot reverts to the pre-failure state.
    store.restore_records(baseline)
    assert [r.get("id") for r in store.records()["learnings"]] == ["A"]


# ---------------------------------------------------------------------------
# REQ-01 / DOD-01, DOD-04 — the engine actually consults the gate
# ---------------------------------------------------------------------------


def test_engine_gate_wired_and_no_slot_leak(tmp_path: Path) -> None:
    """Three overlapping idle cycles run against a limit-2 gate; no slot leaks."""
    store = MemoryStore(tmp_path / "memory.json")
    engine = _make_engine(tmp_path, max_cycles=2, memory=store)

    assert engine._gate.limit == 2, "the engine must size its gate from the config"

    results: list[object] = []
    lock = threading.Lock()

    def run() -> None:
        r = engine.run_cycle()
        with lock:
            results.append(r)

    threads = [threading.Thread(target=run) for _ in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=5.0)

    assert not any(t.is_alive() for t in threads)
    assert len(results) == 3
    assert all(r.admitted for r in results), "idle cycles with a free gate must be admitted"
    assert engine.active_cycles == 0, "no cycle may leak a slot / registration"


def test_engine_blocks_concurrent_cycle_when_slot_full(tmp_path: Path) -> None:
    """A cycle queued for a full slot waits, then completes once admitted."""
    engine = _make_engine(tmp_path, max_cycles=1)
    holder = engine._gate.acquire(Difficulty.EASY, 99, should_stop=engine.should_stop)
    assert holder is not None

    out: dict[str, object] = {}

    def run() -> None:
        out["result"] = engine.run_cycle()

    t = threading.Thread(target=run)
    t.start()
    t.join(timeout=0.2)
    assert t.is_alive(), "the cycle must block while the single slot is held"

    engine._gate.release(holder)
    t.join(timeout=5.0)
    assert not t.is_alive()
    assert out["result"].admitted is True
    assert engine.active_cycles == 0


def test_engine_request_stop_skips_queued_cycle(tmp_path: Path) -> None:
    """After request_stop, run_cycle is skipped (admitted=False) without blocking."""
    engine = _make_engine(tmp_path, max_cycles=2)
    engine.request_stop()

    result = engine.run_cycle()

    assert result.admitted is False, "a stopped engine must not admit a new cycle"
    assert result.steps == 0
    assert engine.active_cycles == 0


# ---------------------------------------------------------------------------
# REQ-05 / DOD-02 — overlapping idle cycles attribute state per cycle id
# ---------------------------------------------------------------------------


def test_engine_per_cycle_attribution_no_cross_cycle_leak(tmp_path: Path) -> None:
    """Two overlapping idle cycles must not corrupt the shared store: the
    per-cycle ``cycle`` attribution on each record survives both cycles."""
    store = MemoryStore(tmp_path / "memory.json")
    # Two previously-committed cycles, each owning its own records.
    store.add_learning({"id": "L1", "text": "a"}, cycle=5)
    store.add_learning({"id": "L2", "text": "b"}, cycle=6)
    watcher = FakeWatcher(("t-a",))
    engine = _make_engine(tmp_path, max_cycles=2, memory=store, watcher=watcher)

    results: list[object] = []
    lock = threading.Lock()

    def run() -> None:
        r = engine.run_cycle()
        with lock:
            results.append(r)

    threads = [threading.Thread(target=run) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=5.0)

    assert not any(t.is_alive() for t in threads)
    cids = sorted(r.cycle for r in results)
    assert len(set(cids)) == 2, "each idle cycle must get a distinct id"
    assert engine.active_cycles == 0, "no cycle may leak a slot / registration"

    # The overlapping idle cycles must not clobber the shared store: both
    # pre-existing records survive, each still attributed to its own cycle.
    docs = store.records()["learnings"]
    assert [r.get("id") for r in docs] == ["L1", "L2"]
    assert [r.get("cycle") for r in docs] == [5, 6]


# ---------------------------------------------------------------------------
# REQ-02 / DOD-01 — the atomic heartbeat seam
# ---------------------------------------------------------------------------


def test_heartbeat_round_trip(tmp_path: Path) -> None:
    path = tmp_path / "heartbeat.json"

    # A missing heartbeat file reads back as (path, None).
    assert read_heartbeat(path) == (path, None)
    assert current_heartbeat(path) is None

    assert write_heartbeat(path, b'{"v": 1}') is True
    assert read_heartbeat(path) == (path, b'{"v": 1}')
    assert current_heartbeat(path) == b'{"v": 1}'

    # A None path means the caller did not opt into heartbeat handling.
    assert read_heartbeat(None) is None
