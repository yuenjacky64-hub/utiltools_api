"""Engine intra-cycle state machine (UTILTOOLS-111).

Covers the task brief REQ-01 .. REQ-05: ``CycleState`` enum and the
``cycle_state`` property (DOD-01), the IDLE -> RUNNING -> DIAGNOSING ->
RUNNING -> IDLE walk of a full cycle (DOD-02), sentinel-record persistence
and restart restore (DOD-03), ``CYCLE_STATE_TRANSITION`` audit events
(DOD-04), rollback to the last-committed state on a critical body failure
(DOD-05), and the legal-only, non-interleaved transition pairs of two
concurrent cycles (DOD-06).
"""

from __future__ import annotations

import threading
from pathlib import Path

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import (
    CYCLE_STATE_SENTINEL_TASK_ID,
    CycleState,
    MaintainerEngine,
)
from ai_maintainer.memory import MemoryStore
from ai_maintainer.tasks.curriculum import (
    Difficulty,
    TaskCurriculum,
    TaskSpec,
    TaskState,
)


class FrozenClock:
    """Clock that only moves when the test moves it."""

    def __init__(self, now: float = 1_000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> float:
        self.now += seconds
        return self.now


class FakeSleeper:
    """``time.sleep`` stand-in: records each injected wait and moves the clock."""

    def __init__(self, clock: FrozenClock) -> None:
        self.clock = clock
        self.calls: list[float] = []

    def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)
        self.clock.advance(seconds)

    @property
    def total(self) -> float:
        return sum(self.calls)


def _spec(name: str, *, steps_required: int = 4) -> TaskSpec:
    return TaskSpec(
        name=name,
        difficulty=Difficulty.EASY,
        steps_required=steps_required,
        progress=1,
        last_proposed_cycle=100,
        state=TaskState.RUNNING,
    )


def _engine(
    tmp_path: Path,
    specs: list[TaskSpec],
    *,
    store_path: Path | None = None,
    memory: MemoryStore | None = None,
) -> MaintainerEngine:
    if memory is None and store_path is not None:
        store_path.parent.mkdir(parents=True, exist_ok=True)
        memory = MemoryStore(store_path)
    clock = FrozenClock()
    config = MaintainerConfig()
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    return MaintainerEngine(
        curriculum,
        config=config,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
        memory=memory,
    )


def _transitions(engine: MaintainerEngine) -> list[dict]:
    return [
        event
        for event in engine.events
        if event["event"] == "CYCLE_STATE_TRANSITION"
    ]


def _pairs(engine: MaintainerEngine) -> list[tuple[str, str]]:
    return [(e["from_state"], e["to_state"]) for e in _transitions(engine)]


def _sentinel_states(store: MemoryStore) -> list[str]:
    # A bare ``MemoryStore(path)`` is a fresh, empty view; refresh it from the
    # document on disk so the reflection sees what the engine persisted.
    store.refresh_from_disk()
    runs = store.records().get("runs") or []
    return [
        record.get("state")
        for record in runs
        if record.get("task_id") == CYCLE_STATE_SENTINEL_TASK_ID
    ]


# ----------------------------------------------------------------- DOD-01


def test_cycle_state_enum_values(tmp_path: Path) -> None:
    """DOD-01/REQ-01: the enum is importable with exactly the five brief
    values, and a fresh engine reports IDLE through the ``cycle_state``
    property whether or not a memory store is installed."""

    assert {state.name for state in CycleState} == {
        "IDLE",
        "RUNNING",
        "DIAGNOSING",
        "RECOVERING",
        "STOPPED",
    }
    assert CycleState.IDLE.value == "IDLE"
    assert CycleState.STOPPED.value == "STOPPED"

    engine = _engine(tmp_path, [])
    assert engine.cycle_state is CycleState.IDLE

    store = MemoryStore(tmp_path / "memory" / "mem.json")
    persisted = _engine(tmp_path, [], memory=store)
    assert persisted.cycle_state is CycleState.IDLE


# ----------------------------------------------------------------- DOD-02


def test_state_transitions_follow_correct_order(tmp_path: Path) -> None:
    """DOD-02/REQ-01: one full cycle that adopts and diagnoses a task walks
    IDLE -> RUNNING -> DIAGNOSING -> RUNNING -> IDLE, and the property
    reflects the current state at every step."""

    engine = _engine(tmp_path, [_spec("task-a")])
    assert engine.cycle_state is CycleState.IDLE

    result = engine.run_cycle()

    assert result.steps >= 1
    assert engine.cycle_state is CycleState.IDLE, "a completed cycle rests in IDLE"
    assert _pairs(engine) == [
        ("IDLE", "RUNNING"),
        ("RUNNING", "DIAGNOSING"),
        ("DIAGNOSING", "RUNNING"),
        ("RUNNING", "IDLE"),
    ]


# ----------------------------------------------------------------- DOD-03


def test_state_persisted_and_restored(tmp_path: Path) -> None:
    """DOD-03/REQ-02: each transition appends a sentinel run record, and a
    restarted engine recovers the last persisted state (here STOPPED, parked
    by a forced stop before the body) and re-enters through the legal
    STOPPED -> IDLE edge."""

    memory_dir = tmp_path / "memory"
    memory_dir.mkdir(parents=True)
    path = memory_dir / "mem.json"
    store = MemoryStore(path)
    engine = _engine(tmp_path, [_spec("task-a")], memory=store)
    engine.request_stop("park test")
    result = engine.run_cycle()
    assert result.steps == 0
    assert engine.cycle_state is CycleState.STOPPED
    assert _sentinel_states(store)[-1] == "STOPPED"

    # A pre-existing document without any sentinel record degrades to IDLE.
    empty_path = tmp_path / "memory" / "empty.json"
    MemoryStore(empty_path)
    pristine = _engine(tmp_path, [], memory=MemoryStore(empty_path))
    assert pristine.cycle_state is CycleState.IDLE
    assert pristine.restore_cycle_state() is CycleState.IDLE

    # Restart: __init__ restores the parked state from the store.
    restarted = _engine(tmp_path, [_spec("task-a")], memory=MemoryStore(path))
    assert restarted.cycle_state is CycleState.STOPPED
    assert restarted.restore_cycle_state() is CycleState.STOPPED

    second = restarted.run_cycle()
    assert second.steps >= 1
    assert restarted.cycle_state is CycleState.IDLE
    assert _sentinel_states(MemoryStore(path))[-1] == "IDLE"


# ----------------------------------------------------------------- DOD-04


def test_audit_trail_entries_created(tmp_path: Path) -> None:
    """DOD-04/REQ-03: every transition carries a CYCLE_STATE_TRANSITION
    event with from_state / to_state / cycle keys, one per edge."""

    engine = _engine(tmp_path, [_spec("task-a")])
    result = engine.run_cycle()

    events = _transitions(engine)
    assert [event for event in events] and len(events) == 4
    for event in events:
        assert set(event) == {"event", "from_state", "to_state", "cycle"}
        assert event["cycle"] == result.cycle
    pairs = [(event["from_state"], event["to_state"]) for event in events]
    assert pairs[0][0] == "IDLE"
    assert pairs[-1][1] == "IDLE"
    # The chain is connected: each from_state is the previous to_state.
    for previous, current in zip(pairs, pairs[1:]):
        assert current[0] == previous[1]


# ----------------------------------------------------------------- DOD-05


def test_rollback_on_critical_failure(tmp_path: Path) -> None:
    """DOD-05/REQ-04: an unhandled critical failure inside the body rolls the
    machine back to the state committed at cycle start (IDLE here), appends
    a CYCLE_STATE_ROLLBACK audit event, persists the target, and leaves the
    machine usable for the next cycle."""

    memory_dir = tmp_path / "memory"
    memory_dir.mkdir(parents=True)
    path = memory_dir / "mem.json"
    store = MemoryStore(path)
    clock = FrozenClock()
    config = MaintainerConfig()
    failing = {"on": True}

    class CriticalProbe(MaintainerEngine):
        def _finish_cycle(self, cycle: int) -> list[str]:
            if failing["on"]:
                raise RuntimeError("critical store corruption")
            return super()._finish_cycle(cycle)

    engine = CriticalProbe(
        TaskCurriculum(specs=[_spec("task-a")], config=config, clock=clock),
        config=config,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
        memory=store,
    )

    try:
        engine.run_cycle()
        raise AssertionError("the critical body failure must propagate")
    except RuntimeError as exc:
        assert "critical store corruption" in str(exc)

    assert engine.cycle_state is CycleState.IDLE, (
        "rollback restores the last committed state, not the in-flight one"
    )
    rollbacks = [
        event
        for event in engine.events
        if event["event"] == "CYCLE_STATE_ROLLBACK"
    ]
    assert len(rollbacks) == 1
    assert rollbacks[0]["to_state"] == "IDLE"
    assert _sentinel_states(store)[-1] == "IDLE"

    failing["on"] = False
    # Usability after rollback: the next cycle runs to completion without a
    # crash and parks the machine back at IDLE.
    recovery = engine.run_cycle()
    assert recovery.outcome == "SUCCESS"
    assert engine.cycle_state is CycleState.IDLE


# ----------------------------------------------------------------- DOD-06


def test_concurrent_cycles_no_interleave(tmp_path: Path) -> None:
    """DOD-06/REQ-05: two overlapping ``run_cycle`` calls serialize on the
    state lock - every recorded transition pair is legal, the event chain
    never jumps, and both on-disk and in-memory state end in IDLE."""

    memory_dir = tmp_path / "memory"
    memory_dir.mkdir(parents=True)
    path = memory_dir / "mem.json"
    store = MemoryStore(path)
    engine = _engine(tmp_path, [_spec("task-a", steps_required=16)], memory=store)
    gate = threading.Barrier(2)
    outcomes: list[str] = []
    errors: list[BaseException] = []

    def _run() -> None:
        gate.wait()
        try:
            result = engine.run_cycle()
            outcomes.append(result.outcome)
        except BaseException as exc:  # pragma: no cover - no exception expected
            errors.append(exc)

    threads = [
        threading.Thread(target=_run, name=f"cycle-{index}") for index in range(2)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(10.0)

    assert not errors, f"concurrent cycles raised: {errors}"
    assert len(outcomes) == 2

    pairs = _pairs(engine)
    assert pairs, "the cycles must have recorded transitions"
    legal = {
        ("IDLE", "RUNNING"),
        ("RUNNING", "DIAGNOSING"),
        ("DIAGNOSING", "RUNNING"),
        ("RUNNING", "RECOVERING"),
        ("DIAGNOSING", "RECOVERING"),
        ("RECOVERING", "RUNNING"),
        ("IDLE", "STOPPED"),
        ("RUNNING", "STOPPED"),
        ("DIAGNOSING", "STOPPED"),
        ("RECOVERING", "STOPPED"),
        ("RUNNING", "IDLE"),
        ("DIAGNOSING", "IDLE"),
        ("RECOVERING", "IDLE"),
        ("STOPPED", "IDLE"),
    }
    for pair in pairs:
        assert pair in legal, f"illegal transition recorded: {pair}"
    for previous, current in zip(pairs, pairs[1:]):
        assert current[0] == previous[1], "events interleaved a transition"

    persisted = _sentinel_states(MemoryStore(path))
    assert persisted, "transitions must persist sentinel records"
    for previous, current in zip(persisted, persisted[1:]):
        assert (previous, current) in legal, (
            f"persisted chain jumped from {previous} to {current}"
        )
    assert engine.cycle_state is CycleState.IDLE
    assert persisted[-1] == "IDLE"
