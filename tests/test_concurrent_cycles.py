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

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.concurrency import (
    CycleAdmissionGate,
    CycleSnapshot,
    current_heartbeat,
    read_heartbeat,
    write_heartbeat,
)
from ai_maintainer.engine import MaintainerEngine
from ai_maintainer.memory.store import MemoryStore
from ai_maintainer.monitor.models import ActiveTask, FrameworkSnapshot
from ai_maintainer.monitor.watcher import FrameworkWatcher
from ai_maintainer.tasks.curriculum import Difficulty, TaskCurriculum


def _watcher(*task_ids: str) -> FrameworkWatcher:
    """The **production** watcher over an injected snapshot (v3 finding 1).

    The capture path under test is ``get_snapshot().active_tasks``, so the
    double must be the real :class:`FrameworkWatcher`; a hand-rolled fake with
    an invented ``active_task_ids`` attribute hid the dead-code capture.
    """

    return FrameworkWatcher(
        FrameworkSnapshot(active_tasks=tuple(ActiveTask(task_id=i) for i in task_ids))
    )


def _make_engine(
    tmp_path: Path,
    *,
    max_cycles: int = 2,
    priority: bool = True,
    memory: MemoryStore | None = None,
    watcher: FrameworkWatcher | None = None,
    heartbeat: str | Path | None = None,
    max_shutdown_wait: float = 5.0,
    audit_path: str | Path | None = None,
) -> MaintainerEngine:
    """Build a lightweight engine for the admission / isolation seams.

    An empty curriculum and a no-op step keep ``run_cycle`` fast and idle
    (it breaks on the first step when nothing is enqueued), which is exactly
    what these concurrency tests need.
    """
    config = MaintainerConfig(
        max_concurrent_cycles=max_cycles,
        priority_preemption_enabled=priority,
        max_shutdown_wait_sec=max_shutdown_wait,
    )
    curriculum = TaskCurriculum(specs=[])
    return MaintainerEngine(
        curriculum,
        config,
        memory=memory,
        watcher=watcher,
        heartbeat_path=heartbeat,
        shutdown_audit_path=audit_path,
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
    """A *queued* cycle observes request_stop and defers instead of blocking.

    Scope of the stop signal (REQ-04, plan §5 "stop deregisters waiters"): it
    releases cycles that are waiting for a slot. It must not touch the
    uncontended path — see ``test_engine_uncontended_cycle_ignores_stale_stop``
    for the pre-task behaviour DOD-09 pins.
    """

    engine = _make_engine(tmp_path, max_cycles=1)
    holder = engine._gate.acquire(Difficulty.EASY, 99, should_stop=engine.should_stop)
    assert holder is not None
    try:
        engine.request_stop()
        result = engine.run_cycle()
    finally:
        engine._gate.release(holder)

    assert result.admitted is False, "a queued cycle must not be admitted after stop"
    assert result.steps == 0
    assert result.cycle > 0, "the deferred cycle still reports its reserved id"
    assert engine.active_cycles == 0
    assert "CYCLE_DEFERRED" in [event["event"] for event in engine.events]


def test_engine_uncontended_cycle_ignores_stale_stop(tmp_path: Path) -> None:
    """A free slot is taken without ever consulting the stop flag (DOD-09).

    ``tests/test_graceful_shutdown.py::test_cycle_after_shutdown_starts_cleanly``
    pins this: a stop published by an earlier cycle must not turn a later,
    uncontended ``run_cycle()`` into a no-op.
    """

    engine = _make_engine(tmp_path, max_cycles=2)
    engine.request_stop()
    polls: list[bool] = []

    def should_stop() -> bool:
        polls.append(True)
        return True

    token = engine._gate.acquire(Difficulty.EASY, 1, should_stop=should_stop)
    assert token is not None, "a free slot must be granted before the stop poll"
    assert polls == [], "the uncontended path must not poll should_stop()"
    engine._gate.release(token)


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
    watcher = _watcher("t-a", "t-b")
    engine = _make_engine(tmp_path, max_cycles=2, memory=store, watcher=watcher)

    # v3 finding 1: the *production* watcher (``get_snapshot().active_tasks``)
    # must actually reach the snapshot - asserting the capture would have
    # caught the dead ``active_task_ids`` duck-type.
    captured = engine._capture_snapshot(cycle=7, difficulty=Difficulty.EASY)
    assert captured.active_task_ids == ("t-a", "t-b")

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


# ---------------------------------------------------------------------------
# REQ-04 / DOD-06 (wind-down) and DOD-07 (lock release) — multi-cycle wind-down
# ---------------------------------------------------------------------------


class _CountingLock:
    """Fake file lock recording how many times it was released."""

    def __init__(self) -> None:
        self.releases = 0

    def release(self) -> None:
        self.releases += 1


def test_wind_down_waits_for_two_inflight_cycles(tmp_path: Path) -> None:
    """The wind-down blocks until *both* registered cycles leave (DOD-06)."""

    audit = tmp_path / "audit" / "maintainer.log"
    engine = _make_engine(
        tmp_path,
        max_cycles=2,
        max_shutdown_wait=5.0,
        audit_path=audit,
    )
    engine._register_cycle(1, Difficulty.EASY, CycleSnapshot(cycle=1))
    engine._register_cycle(2, Difficulty.COMPLEX, CycleSnapshot(cycle=2))
    assert engine.active_cycles == 2

    finished = threading.Event()
    reports: list[object] = []

    def wind_down() -> None:
        reports.append(engine.shutdown())
        finished.set()

    thread = threading.Thread(target=wind_down)
    thread.start()
    thread.join(timeout=0.15)
    assert not finished.is_set(), "the wind-down must wait for the two cycles"

    engine._deregister_cycle(1)
    thread.join(timeout=0.15)
    assert not finished.is_set(), "one cycle is still in flight; keep waiting"

    engine._deregister_cycle(2)
    thread.join(timeout=5.0)
    assert finished.is_set() and not thread.is_alive()
    assert engine.active_cycles == 0

    report = reports[0]
    assert report.timed_out is False, "both cycles exited inside the budget"
    assert report.audit_written is True
    lines = [line for line in audit.read_text(encoding="utf-8").splitlines() if line]
    assert len(lines) == 1, "one wind-down writes exactly one audit line"
    assert engine.events[-1]["event"] == "SHUTDOWN_COMPLETE"


def test_wind_down_releases_each_file_lock_once(tmp_path: Path) -> None:
    """Every tracked lock is released exactly once, even across repeats (DOD-07)."""

    engine = _make_engine(
        tmp_path,
        max_cycles=2,
        audit_path=tmp_path / "maintainer.log",
    )
    first, second = _CountingLock(), _CountingLock()
    engine.register_file_lock("task-a", first)
    engine.register_file_lock("task-b", second)

    report = engine.shutdown()
    assert report.released_lock_keys == ("task-a", "task-b")
    assert first.releases == 1 and second.releases == 1
    assert engine.file_locks == ()

    # Idempotent: the cached report is returned and nothing is released twice.
    again = engine.shutdown()
    assert again is report
    assert first.releases == 1 and second.releases == 1


def test_run_forever_multi_cycle_mode_writes_one_audit_line(tmp_path: Path) -> None:
    """``run_forever`` with a peer cycle in flight still winds down once (DOD-06)."""

    audit = tmp_path / "maintainer.log"
    engine = _make_engine(
        tmp_path,
        max_cycles=2,
        audit_path=audit,
    )
    peer_done = threading.Event()

    def peer() -> None:
        engine._register_cycle(90, Difficulty.HARD, CycleSnapshot(cycle=90))
        peer_done.wait(5.0)
        engine._deregister_cycle(90)

    thread = threading.Thread(target=peer)
    thread.start()
    while engine.active_cycles != 1:
        time.sleep(0.005)

    real_run_cycle = engine.run_cycle
    calls: list[int] = []

    def one_cycle() -> object:
        calls.append(1)
        engine.request_stop()
        peer_done.set()
        return real_run_cycle()

    engine.run_cycle = one_cycle  # type: ignore[method-assign]
    report = engine.run_forever(interval_sec=0.0)
    thread.join(timeout=5.0)

    assert calls == [1], "the stop request ends the loop after one cycle"
    assert report.timed_out is False
    events = [event["event"] for event in engine.events]
    assert events.count("SHUTTING_DOWN") == 1
    assert events.count("SHUTDOWN_COMPLETE") == 1
    lines = [line for line in audit.read_text(encoding="utf-8").splitlines() if line]
    assert len(lines) == 1, "multi-cycle mode must not duplicate the audit line"
    assert engine.active_cycles == 0


# ---------------------------------------------------------------------------
# REQ-02 / plan §4.3 — late wiring seams for the watcher and heartbeat path
# ---------------------------------------------------------------------------


def test_install_watcher_and_heartbeat_path_seams(tmp_path: Path) -> None:
    """An engine built without the monitor state can be wired afterwards.

    v3 finding 2: plan §4.3 names ``install_watcher()`` /
    ``install_heartbeat_path()`` alongside ``install_signal_gate()`` /
    ``install_memory()``; without them the production API layer (which builds
    the engine before the monitor snapshot or the state root exists) could not
    enable watcher/heartbeat capture at all.
    """

    heartbeat = tmp_path / "state" / "maintainer_heartbeat.json"
    heartbeat.parent.mkdir()
    heartbeat.write_bytes(b'{"beat": 1}')

    engine = _make_engine(tmp_path)  # no watcher, no heartbeat path
    assert engine._capture_snapshot(cycle=1, difficulty=Difficulty.EASY).heartbeat is None

    engine.install_watcher(_watcher("t-a"))
    engine.install_heartbeat_path(heartbeat)
    snapshot = engine._capture_snapshot(cycle=2, difficulty=Difficulty.EASY)
    assert snapshot.active_task_ids == ("t-a",)
    assert snapshot.heartbeat is not None
    assert snapshot.heartbeat[0] == heartbeat
    assert snapshot.heartbeat[1] == b'{"beat": 1}'

    # ``None`` uninstalls both seams (pre-task behaviour restored).
    engine.install_watcher(None)
    engine.install_heartbeat_path(None)
    snapshot = engine._capture_snapshot(cycle=3, difficulty=Difficulty.EASY)
    assert snapshot.active_task_ids == ()
    assert snapshot.heartbeat is None


# ---------------------------------------------------------------------------
# REQ-04 / DOD-08 — the store is thread-safe under concurrent writers
# ---------------------------------------------------------------------------


def test_store_concurrent_appends_from_two_threads(tmp_path: Path) -> None:
    """Two threads appending all three collections keep every record (DOD-08).

    v3 finding 4: nothing drove ``add_run`` / ``add_bug_fix`` /
    ``add_learning`` from two threads, so the ``_write_lock`` protection REQ-05
    relies on (overlapping cycles attribute to their own ``cycle``) was
    unverified. Every append must survive and keep its own attribution.
    """

    store = MemoryStore(tmp_path / "memory.json")
    barrier = threading.Barrier(2)
    per_thread = 25

    def writer(cycle: int) -> None:
        barrier.wait(5.0)
        for n in range(per_thread):
            store.add_run({"id": f"run-{cycle}-{n}"}, cycle=cycle)
            store.add_bug_fix({"id": f"fix-{cycle}-{n}"}, cycle=cycle)
            store.add_learning({"id": f"learn-{cycle}-{n}"}, cycle=cycle)

    threads = [threading.Thread(target=writer, args=(c,)) for c in (11, 12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10.0)
    assert not any(t.is_alive() for t in threads)

    docs = store.records()
    for name in ("runs", "bug_fixes", "learnings"):
        assert len(docs[name]) == 2 * per_thread, f"{name} lost an append"
        by_cycle = {c: 0 for c in (11, 12)}
        for record in docs[name]:
            by_cycle[record["cycle"]] += 1
        assert by_cycle == {11: per_thread, 12: per_thread}, f"{name} mis-attributed"


# ---------------------------------------------------------------------------
# REQ-02 / INV-CONC-5 — a failed heartbeat restore is not reported as success
# ---------------------------------------------------------------------------


def test_failed_heartbeat_restore_is_reported(tmp_path: Path) -> None:
    """``write_heartbeat`` returning ``False`` yields ``SNAPSHOT_RESTORE_FAILED``.

    v3 finding 5: the audit condition used to be ``heartbeat_result is not None``
    with ``error is None``, so a restore that could not be written was logged as
    ``SNAPSHOT_RESTORED`` (``removed: 0``) - a silent data-loss report.
    """

    engine = _make_engine(tmp_path)
    missing = tmp_path / "does-not-exist" / "heartbeat.json"
    snapshot = CycleSnapshot(cycle=4, heartbeat=(missing, b'{"beat": 2}'))

    engine._restore_snapshot(snapshot, trusted_cycle=4, committed=False)

    events = [event for event in engine.events if event["event"].startswith("SNAPSHOT_")]
    assert len(events) == 1
    event = events[0]
    assert event["event"] == "SNAPSHOT_RESTORE_FAILED"
    assert event["heartbeat_restored"] is False
    assert event["error"] == "HEARTBEAT_RESTORE_FAILED"
    assert not missing.exists(), "the restore must not invent the parent directory"


# ---------------------------------------------------------------------------
# REQ-01 / DOD-01 — the config default, env override and validation
# ---------------------------------------------------------------------------


def test_max_concurrent_cycles_config_contract(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Default 2, ``MAINTAINER_MAX_CONCURRENT_CYCLES`` override, ``>= 1`` check.

    v3 finding 3: every other test passed the value explicitly, so the DOD-01
    default and the ``__post_init__`` guard were unverified.
    """

    from ai_maintainer import config as config_module

    monkeypatch.setattr(config_module, "_load_must_config", lambda: {})

    assert MaintainerConfig().max_concurrent_cycles == 2, "brief default is 2"
    assert (
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={"MAINTAINER_MAX_CONCURRENT_CYCLES": "3"},
        ).max_concurrent_cycles
        == 3
    ), "the documented env override must win over the default"
    # A CLI-style override still beats the environment.
    assert (
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={"MAINTAINER_MAX_CONCURRENT_CYCLES": "3"},
            max_concurrent_cycles=4,
        ).max_concurrent_cycles
        == 4
    )
    for bad in (0, -1):
        with pytest.raises(ValueError):
            MaintainerConfig(max_concurrent_cycles=bad)


def test_snapshot_active_cycles_rows(tmp_path: Path) -> None:
    """``snapshot()["active_cycles"]`` carries one attributed row per live cycle.

    Plan §4 row shape ``{cycle, task, difficulty, admitted_at}`` (v3 finding 4,
    second half): the operator view must name the cycle, the task its slot
    adopted, the admission difficulty and the admission timestamp.
    """

    engine = _make_engine(tmp_path)
    engine._register_cycle(
        7, Difficulty.COMPLEX, CycleSnapshot(cycle=7, admitted_at=123.5)
    )
    engine._register_cycle(8, Difficulty.EASY, CycleSnapshot(cycle=8, admitted_at=200.0))
    try:
        rows = {row["cycle"]: row for row in engine.snapshot()["active_cycles"]}
        assert set(rows) == {7, 8}
        assert rows[7]["difficulty"] == "COMPLEX"
        assert rows[7]["admitted_at"] == 123.5
        assert rows[8]["difficulty"] == "EASY"
        assert rows[8]["admitted_at"] == 200.0
        for row in rows.values():
            assert {"cycle", "task", "difficulty", "admitted_at"} <= set(row)
    finally:
        engine._deregister_cycle(7)
        engine._deregister_cycle(8)
    assert engine.snapshot()["active_cycles"] == []
