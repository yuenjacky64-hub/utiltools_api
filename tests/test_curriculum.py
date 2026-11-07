"""Priority, preemption and aging behaviour for the maintainer task lifecycle.

Covers brief REQ-01 .. REQ-05 (DOD-01 .. DOD-10 in the plan). The clock is
always injected so the 300 s aging threshold is asserted at the boundary
(300.0 s no boost, 300.001 s boost) without sleeping.
"""

from __future__ import annotations

import json
import threading

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import MaintainerEngine
from ai_maintainer.tasks.curriculum import (
    NON_TERMINAL,
    Difficulty,
    Priority,
    TaskCurriculum,
    TaskSpec,
    TaskState,
    effective_priority,
)


class FrozenClock:
    """Clock that only moves when the test moves it."""

    def __init__(self, now: float = 0.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> float:
        self.now += seconds
        return self.now


def _spec(
    name: str,
    difficulty: Difficulty = Difficulty.EASY,
    *,
    steps_required: int = 4,
    last_proposed_cycle: int | None = 100,
    state: TaskState = TaskState.PENDING,
) -> TaskSpec:
    """Spec with recency disabled by default (``last_proposed_cycle`` ahead)."""

    return TaskSpec(
        name=name,
        difficulty=difficulty,
        steps_required=steps_required,
        last_proposed_cycle=last_proposed_cycle,
        state=state,
    )


def test_task_spec_priority_is_validated_and_serializes() -> None:
    """DOD-01: priority is validated, difficulty-derived and round-trips."""

    assert TaskSpec(name="complex", difficulty=Difficulty.COMPLEX).priority is Priority.HIGH
    assert TaskSpec(name="hard", difficulty=Difficulty.HARD).priority is Priority.NORMAL
    assert TaskSpec(name="medium", difficulty=Difficulty.MEDIUM).priority is Priority.LOW
    assert TaskSpec(name="easy").priority is Priority.LOW
    assert Priority.parse("high") is Priority.HIGH
    assert Priority.parse(Priority.NORMAL) is Priority.NORMAL

    spec = _spec("complex", Difficulty.COMPLEX)
    data = spec.to_dict()
    assert data["priority"] == "HIGH"
    assert data["difficulty"] == "COMPLEX"
    assert json.loads(json.dumps(data))["priority"] == "HIGH"

    restored = TaskSpec.from_dict(data)
    assert restored.to_dict() == data
    assert restored.priority is Priority.HIGH

    message = "priority must be one of HIGH, NORMAL, LOW"
    with pytest.raises(ValueError, match=message):
        Priority.parse("URGENT")
    with pytest.raises(ValueError, match=message):
        TaskSpec(name="bad", priority="URGENT")
    with pytest.raises(ValueError, match=message):
        TaskSpec.from_dict({**data, "priority": "urgent"})


def test_curriculum_assigns_priority_by_difficulty() -> None:
    """DOD-02: base rank follows difficulty; highest rank is returned first."""

    clock = FrozenClock(1000.0)
    config = MaintainerConfig()
    specs = [
        _spec("complex", Difficulty.COMPLEX, last_proposed_cycle=0),
        _spec("hard", Difficulty.HARD, last_proposed_cycle=0),
        _spec("medium", Difficulty.MEDIUM, last_proposed_cycle=0),
        _spec("easy", Difficulty.EASY, last_proposed_cycle=0),
    ]
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)

    assert [task.priority for task in specs] == [
        Priority.HIGH,
        Priority.NORMAL,
        Priority.LOW,
        Priority.LOW,
    ]
    # No boost before the recency/aging thresholds: effective == base.
    assert (
        effective_priority(specs[3], cycle=0, now=clock(), config=config)
        is Priority.LOW
    )

    order = []
    for _ in range(4):
        proposed = curriculum.generate_task()
        assert proposed is not None
        order.append(proposed.name)
        curriculum.apply_state(proposed.name, TaskState.COMPLETED)
    assert order == ["complex", "hard", "medium", "easy"]
    assert curriculum.generate_task() is None


def test_curriculum_recency_boost_raises_low_to_normal() -> None:
    """DOD-03: a LOW task not proposed for N cycles ranks as NORMAL."""

    clock = FrozenClock(500.0)
    config = MaintainerConfig()
    stale = _spec("stale", Difficulty.EASY, last_proposed_cycle=0)
    fresh = _spec("fresh", Difficulty.EASY, last_proposed_cycle=2)
    TaskCurriculum(specs=[stale, fresh], config=config, clock=clock)

    assert effective_priority(stale, cycle=2, now=clock(), config=config) is Priority.LOW
    assert (
        effective_priority(stale, cycle=3, now=clock(), config=config)
        is Priority.NORMAL
    )
    assert effective_priority(fresh, cycle=3, now=clock(), config=config) is Priority.LOW

    # Selection: a recency-boosted LOW task outranks a fresh LOW task.
    boosted_config = MaintainerConfig(recency_boost_cycles=1)
    recent = _spec("recent", Difficulty.EASY, last_proposed_cycle=1)
    aged = _spec("aged", Difficulty.EASY, last_proposed_cycle=0)
    picker = TaskCurriculum(specs=[recent, aged], config=boosted_config, clock=clock)
    chosen = picker.generate_task()
    assert chosen is aged
    assert aged.last_proposed_cycle == 1
    assert recent.last_proposed_cycle == 1


def test_aging_boost_raises_normal_to_high_at_threshold() -> None:
    """DOD-04: strict ``>`` aging threshold; terminal tasks never age."""

    clock = FrozenClock(0.0)
    config = MaintainerConfig()
    hard = _spec("hard", Difficulty.HARD, last_proposed_cycle=0)
    easy = _spec("easy", Difficulty.EASY, last_proposed_cycle=0)
    curriculum = TaskCurriculum(specs=[hard, easy], config=config, clock=clock)

    assert hard.pending_since == 0.0
    assert effective_priority(hard, cycle=0, now=300.0, config=config) is Priority.NORMAL
    assert effective_priority(hard, cycle=0, now=300.001, config=config) is Priority.HIGH
    assert effective_priority(easy, cycle=0, now=300.001, config=config) is Priority.NORMAL
    # HIGH is the ceiling: aging never invents a rank above it.
    complex_task = _spec("complex", Difficulty.COMPLEX, last_proposed_cycle=0)
    assert (
        effective_priority(complex_task, cycle=0, now=999.0, config=config)
        is Priority.HIGH
    )

    # A task that left the non-terminal states is never aged.
    curriculum.apply_state("hard", TaskState.COMPLETED)
    assert hard.state is TaskState.COMPLETED
    assert hard.state not in NON_TERMINAL
    assert effective_priority(hard, cycle=0, now=999.0, config=config) is Priority.NORMAL
    assert effective_priority(easy, cycle=0, now=0.0, config=config) is Priority.LOW


def test_late_added_spec_aging_clock_starts_at_first_observation() -> None:
    """MUST_FIX-3: an unstamped spec entering ``specs`` later is not "aged"."""

    clock = FrozenClock(10_000.0)
    config = MaintainerConfig(max_steps_per_cycle=1)
    curriculum = TaskCurriculum(specs=[], config=config, clock=clock)
    late = TaskSpec(
        name="late",
        difficulty=Difficulty.HARD,
        steps_required=4,
        last_proposed_cycle=100,
    )

    # Unobserved is not "pending since epoch": a mid-scale monotonic clock
    # must not fake a HIGH rank for a task that just arrived.
    assert late.pending_since is None
    assert effective_priority(late, cycle=0, now=clock(), config=config) is Priority.NORMAL

    # ``generate_task`` stamps the first observation moment.
    curriculum.specs.append(late)
    assert curriculum.generate_task() is late
    assert late.pending_since == 10_000.0
    assert effective_priority(late, cycle=1, now=clock(), config=config) is Priority.NORMAL

    # The engine stamps too, and never re-stamps an already-stamped task.
    engine = MaintainerEngine(curriculum, config=config, clock=clock)
    assert engine.run_cycle().monitored == "late"
    assert late.pending_since == 10_000.0

    late_second = TaskSpec(
        name="late-2",
        difficulty=Difficulty.MEDIUM,
        steps_required=4,
        last_proposed_cycle=100,
    )
    curriculum.specs.append(late_second)
    assert late_second.pending_since is None
    engine.run_cycle()
    assert late_second.pending_since == 10_000.0

    # Aging counts from the stamp, so one rank is bought after the threshold.
    clock.advance(300.001)
    assert effective_priority(late, cycle=1, now=clock(), config=config) is Priority.HIGH
    assert (
        effective_priority(late_second, cycle=1, now=clock(), config=config)
        is Priority.NORMAL
    )


def test_task_spec_rejects_non_finite_pending_since_and_bool_ints() -> None:
    """NIT-1/NIT-2: no silent defaulting on the aging clock, bools excluded."""

    for bad in (float("nan"), float("inf"), float("-inf"), -1.0):
        with pytest.raises(ValueError, match="pending_since must be None or a finite float"):
            TaskSpec(name="bad-clock", pending_since=bad)
    with pytest.raises(ValueError, match="progress must be an int >= 0"):
        TaskSpec(name="bool-progress", progress=True)
    with pytest.raises(ValueError, match="steps_required must be an int >= 1"):
        TaskSpec(name="bool-steps", steps_required=True)

    # A terminal task never ages, stamped or not.
    terminal = TaskSpec(
        name="done",
        difficulty=Difficulty.HARD,
        state=TaskState.COMPLETED,
        last_proposed_cycle=9,
    )
    assert terminal.pending_since is None
    assert (
        effective_priority(terminal, cycle=9, now=10**9, config=MaintainerConfig())
        is Priority.NORMAL
    )


def test_maintainer_config_aging_interval_default_is_300000_ms() -> None:
    """DOD-05: the aging knob is configurable and defaulted to 300000 ms."""

    config = MaintainerConfig()
    assert config.prioritization_aging_interval_ms == 300000
    assert config.aging_threshold_seconds == pytest.approx(300.0)
    assert config.recency_boost_cycles == 3
    assert config.max_steps_per_cycle == 8
    assert config.priority_preemption_enabled is True

    custom = MaintainerConfig(prioritization_aging_interval_ms=1000)
    assert custom.aging_threshold_seconds == pytest.approx(1.0)

    with pytest.raises(ValueError, match="prioritization_aging_interval_ms must be >= 0"):
        MaintainerConfig(prioritization_aging_interval_ms=-1)
    with pytest.raises(ValueError, match="recency_boost_cycles must be >= 1"):
        MaintainerConfig(recency_boost_cycles=-1)
    with pytest.raises(ValueError, match="max_steps_per_cycle must be >= 1"):
        MaintainerConfig(max_steps_per_cycle=0)


def test_engine_monitors_higher_priority_task_first() -> None:
    """DOD-06: with contention, HIGH is monitored before NORMAL before LOW."""

    clock = FrozenClock(1000.0)
    config = MaintainerConfig(max_steps_per_cycle=1)
    specs = [
        _spec("low", Difficulty.EASY, steps_required=3),
        _spec("normal", Difficulty.HARD, steps_required=3),
        _spec("high", Difficulty.COMPLEX, steps_required=3),
    ]
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    engine = MaintainerEngine(curriculum, config=config, clock=clock)

    first = engine.run_cycle()
    assert (first.monitored, first.priority) == ("high", "HIGH")
    assert first.preempted is None

    curriculum.apply_state("high", TaskState.COMPLETED)
    second = engine.run_cycle()
    assert (second.monitored, second.priority) == ("normal", "NORMAL")

    curriculum.apply_state("normal", TaskState.COMPLETED)
    third = engine.run_cycle()
    assert (third.monitored, third.priority) == ("low", "LOW")
    assert [step.cycle for step in (first, second, third)] == [1, 2, 3]


def test_engine_preempts_lower_priority_and_preserves_state() -> None:
    """DOD-07: a pending HIGH task preempts monitoring of a LOW task."""

    clock = FrozenClock(1000.0)
    config = MaintainerConfig(max_steps_per_cycle=1)
    low = _spec("low", Difficulty.EASY, steps_required=5)
    high = _spec("high", Difficulty.COMPLEX, steps_required=2, state=TaskState.CANCELLED)
    curriculum = TaskCurriculum(specs=[low, high], config=config, clock=clock)
    engine = MaintainerEngine(curriculum, config=config, clock=clock)

    first = engine.run_cycle()
    assert (first.monitored, first.preempted) == ("low", None)
    assert low.progress == 1
    pending_since = low.pending_since

    curriculum.apply_state("high", TaskState.PENDING)
    second = engine.run_cycle()
    assert second.preempted == "low"
    assert second.monitored == "high"
    assert second.priority == "HIGH"
    assert low.state is TaskState.PENDING
    assert low.progress == 1
    assert low.pending_since == pending_since
    assert low.preempted_count == 1
    assert engine.events[0] == {"event": "PREEMPTED", "task": "low", "cycle": 2}

    # The preempted task is still resumable once the HIGH task drains.
    third = engine.run_cycle()
    assert third.monitored == "high"
    assert high.state is TaskState.COMPLETED
    fourth = engine.run_cycle()
    assert fourth.monitored == "low"
    assert low.progress == 2


def test_low_priority_task_is_never_blocked_indefinitely() -> None:
    """DOD-08: aging keeps a continuously-preempted LOW task from starving."""

    clock = FrozenClock(0.0)
    config = MaintainerConfig(max_steps_per_cycle=1)
    # ``last_proposed_cycle=None`` = never proposed, so the recency boost is
    # always on; combined with aging (>= 300 s) LOW reaches HIGH.
    low = _spec("low", Difficulty.EASY, steps_required=3, last_proposed_cycle=None)
    high = _spec("high", Difficulty.COMPLEX, steps_required=1)
    curriculum = TaskCurriculum(specs=[low, high], config=config, clock=clock)
    engine = MaintainerEngine(curriculum, config=config, clock=clock)

    levels: list[int | None] = []
    adopted: list[str | None] = []
    for _ in range(12):
        clock.advance(60.0)
        if high.state is TaskState.COMPLETED:
            curriculum.apply_state("high", TaskState.PENDING)
        result = engine.run_cycle()
        adopted.append(result.monitored)
        alive = low.state in NON_TERMINAL
        levels.append(
            effective_priority(low, cycle=0, now=clock(), config=config).value
            if alive
            else None
        )

    observed = [level for level in levels if level is not None]
    assert observed == sorted(observed), "aged priority must never regress"
    assert observed[0] == Priority.NORMAL.value
    assert observed[-1] == Priority.HIGH.value
    assert max(observed) <= Priority.HIGH.value
    first_low_cycle = adopted.index("low") + 1
    assert first_low_cycle > 1, "HIGH task must be served first"
    assert levels[first_low_cycle - 1] == Priority.HIGH.value
    assert low.preempted_count == 0
    assert low.state is TaskState.COMPLETED
    assert low.progress == low.steps_required
    assert engine.snapshot()["monitored"] is None


def test_concurrent_run_cycle_preserves_priority_invariant() -> None:
    """DOD-09: no LOW task is ever monitored while a HIGH one is pending."""

    clock = FrozenClock(1000.0)
    config = MaintainerConfig(max_steps_per_cycle=2)
    specs = [
        _spec("a", Difficulty.EASY, steps_required=4, last_proposed_cycle=1000),
        _spec("b", Difficulty.HARD, steps_required=4, last_proposed_cycle=1000),
        _spec("c", Difficulty.COMPLEX, steps_required=4, last_proposed_cycle=1000),
    ]
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    engine = MaintainerEngine(curriculum, config=config, clock=clock)
    by_name = {task.name: task for task in specs}

    violations: list[tuple[str, str, str]] = []
    errors: list[BaseException] = []
    stop = threading.Event()

    def watcher() -> None:
        while not stop.is_set():
            snap = engine.snapshot()
            monitored = snap["monitored"]
            if monitored is None:
                continue
            level = effective_priority(
                by_name[monitored], cycle=0, now=clock(), config=config
            ).value
            for name, priority_name in snap["pending"].items():
                if Priority.parse(priority_name).value > level:
                    violations.append((monitored, name, priority_name))

    def worker() -> None:
        try:
            for _ in range(5):
                engine.run_cycle()
        except BaseException as exc:  # pragma: no cover - asserted below
            errors.append(exc)

    watcher_thread = threading.Thread(target=watcher, daemon=True)
    workers = [threading.Thread(target=worker) for _ in range(4)]
    watcher_thread.start()
    for thread in workers:
        thread.start()
    for thread in workers:
        thread.join(timeout=30)
    stop.set()
    watcher_thread.join(timeout=5)

    assert errors == []
    assert all(not thread.is_alive() for thread in workers)
    assert violations == []
    for task in specs:
        assert task.state is TaskState.COMPLETED
        assert task.progress == task.steps_required
    completed = [event["task"] for event in engine.events if event["event"] == "COMPLETED"]
    assert sorted(completed) == ["a", "b", "c"]


def test_curriculum_generate_task_returns_none_and_handles_all_easy_specs() -> None:
    """DOD-10: empty/exhausted corpora return None; all-easy corpora are LOW."""

    clock = FrozenClock(50.0)
    config = MaintainerConfig()
    empty = TaskCurriculum(specs=[], config=config, clock=clock)
    assert empty.generate_task() is None
    assert empty.cycle == 1

    specs = [
        _spec(f"task-{index}", Difficulty.EASY, steps_required=1, last_proposed_cycle=0)
        for index in range(3)
    ]
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    assert {task.priority for task in specs} == {Priority.LOW}

    picked = [curriculum.generate_task().name for _ in range(3)]
    assert sorted(picked) == ["task-0", "task-1", "task-2"]
    for spec in specs:
        curriculum.apply_state(spec.name, TaskState.COMPLETED)
    assert curriculum.generate_task() is None
    assert all(spec.state is TaskState.COMPLETED for spec in specs)
