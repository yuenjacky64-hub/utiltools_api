"""Stale-task detection and recovery for the maintainer poll tick.

Covers brief REQ-01 .. REQ-04 (DOD-01 .. DOD-08 in the plan). The clock is
always injected, so the 300 s stale timeout and the 60 s recovery grace are
asserted at their boundaries without sleeping, and the monitor step is
scripted through the existing ``_monitor_step`` seam so no production seam had
to change.
"""

from __future__ import annotations

import time

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import MaintainerEngine, error_fingerprint
from ai_maintainer.tasks.curriculum import (
    STALE_ELIGIBLE,
    Difficulty,
    TaskCurriculum,
    TaskSpec,
    TaskState,
    progress_age_sec,
    stamp_progress,
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


class ScriptedEngine(MaintainerEngine):
    """Engine whose monitor step raises the scripted error of a task, if any.

    It also snapshots the stale bookkeeping *as the poll tick observed it*
    (the step body runs after ``_evaluate_stale`` and before the commit), which
    is where "computed per iteration" is actually visible.
    """

    def __init__(self, *args, errors=None, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.errors: dict[str, BaseException] = dict(errors or {})
        self.stepped: list[str] = []
        self.observed_age: list[float] = []
        self.observed_stale_since: list[float | None] = []

    def _monitor_step(self, task: TaskSpec) -> None:
        self.stepped.append(task.name)
        self.observed_age.append(task.last_progress_age_sec)
        self.observed_stale_since.append(task.stale_since)
        error = self.errors.get(task.name)
        if error is not None:
            raise error
        return None


def _build(
    specs: list[TaskSpec],
    *,
    errors: dict[str, BaseException] | None = None,
    clock: FrozenClock | None = None,
    **config_kwargs,
) -> tuple[ScriptedEngine, TaskCurriculum, FrozenClock]:
    clock = clock if clock is not None else FrozenClock(100.0)
    config_kwargs.setdefault("max_steps_per_cycle", 8)
    config = MaintainerConfig(**config_kwargs)
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    engine = ScriptedEngine(curriculum, config=config, clock=clock, errors=errors)
    return engine, curriculum, clock


def _stale_events(engine: MaintainerEngine) -> list[dict]:
    return [event for event in engine.events if event["event"].startswith("STALE")]


def test_task_spec_tracks_progress_age_fields() -> None:
    """DOD-04 / REQ-01: fields default, round-trip, and the eligible set."""

    fresh = TaskSpec(name="fresh")
    assert fresh.last_progress_at is None
    assert fresh.last_progress_age_sec == 0.0
    assert fresh.stale_since is None
    # An unobserved task reads 0.0, never the age of the clock's epoch.
    assert progress_age_sec(fresh, 10_000.0) == 0.0

    data = fresh.to_dict()
    assert {"last_progress_at", "last_progress_age_sec", "stale_since"} <= set(data)
    assert TaskSpec.from_dict(data).to_dict() == data

    stamp_progress(fresh, 123.0)
    assert fresh.last_progress_at == 123.0
    assert fresh.last_progress_age_sec == 0.0
    assert fresh.stale_since is None
    assert progress_age_sec(fresh, 153.0) == pytest.approx(30.0)
    assert STALE_ELIGIBLE == {TaskState.IN_PROGRESS, TaskState.RUNNING}

    with pytest.raises(
        ValueError, match="last_progress_age_sec must be a finite float >= 0"
    ):
        TaskSpec(name="bad-age", last_progress_age_sec=-1.0)
    with pytest.raises(ValueError, match="stale_since must be None or a finite float"):
        TaskSpec(name="bad-latch", stale_since=-1.0)


def test_poll_tick_records_progress_age_per_iteration() -> None:
    """DOD-03 / REQ-01: the age is recomputed from the clock every tick."""

    slow = _spec("slow", steps_required=10)
    engine, _, clock = _build([slow], max_steps_per_cycle=1)

    assert engine.run_cycle().monitored == "slow"
    assert engine.observed_age == [0.0]  # adopted *this* tick
    assert slow.last_progress_age_sec == 0.0  # committed step resets it

    clock.advance(10.0)
    engine.run_cycle()
    assert engine.observed_age == [0.0, pytest.approx(10.0)]
    assert slow.last_progress_age_sec == 0.0

    # Without an intervening commit the same clock reference keeps aging by
    # exactly the elapsed time: the age is recomputed per iteration, never
    # cached from the first observation.
    clock.advance(10.0)
    assert engine.sweep_stale_tasks()["scanned"] == 1
    assert slow.last_progress_age_sec == pytest.approx(10.0)
    clock.advance(10.0)
    assert engine.sweep_stale_tasks()["scanned"] == 1
    assert slow.last_progress_age_sec == pytest.approx(20.0)
    assert slow.stale_since is None  # 20 s is nowhere near the timeout


def test_stale_warning_logged_once_when_age_exceeds_timeout() -> None:
    """DOD-01 / REQ-02: strict ``>`` boundary, one event per breach."""

    for advance, expected in ((300.0, 0), (300.001, 1)):
        slow = _spec("slow", steps_required=10)
        engine, _, clock = _build([slow], max_steps_per_cycle=1)
        engine.run_cycle()
        clock.advance(advance)
        engine.run_cycle()

        stale = [e for e in engine.events if e["event"] == "STALE_TASK"]
        assert len(stale) == expected
        assert engine.observed_age[-1] == pytest.approx(advance)
        if expected:
            assert stale[0] == {
                "event": "STALE_TASK",
                "task": "slow",
                "cycle": 2,
                "age_sec": pytest.approx(advance),
                "timeout_sec": 300.0,
            }
            # The breach latch is set as the tick completes the evaluation.
            assert engine.observed_stale_since[-1] == pytest.approx(clock.now)
            # Idempotent: no duplicate warning at the same clock value.
            before = len(engine.events)
            engine.sweep_stale_tasks()
            engine.sweep_stale_tasks()
            assert len(engine.events) == before
        else:
            assert engine.observed_stale_since[-1] is None


def test_stale_task_transitions_to_failed_with_audit_entry() -> None:
    """DOD-02 / REQ-03: grace boundary, ``Error`` status, audit citation."""

    slow = _spec("slow", steps_required=10)
    engine, _, clock = _build([slow], max_steps_per_cycle=1)
    engine.run_cycle()
    start = clock.now

    # Exactly 360 s: stale, but the 60 s grace window is a strict bound.
    outcome = engine.sweep_stale_tasks(now=start + 360.0)
    assert outcome["scanned"] == 1
    assert outcome["stale"] == 1
    assert outcome["recovered"] == 0
    assert slow.state is TaskState.RUNNING
    assert [e["event"] for e in engine.events].count("STALE_TASK") == 1
    assert _stale_events(engine)[-1]["event"] == "STALE_TASK"

    outcome = engine.sweep_stale_tasks(now=start + 360.001)
    assert outcome["recovered"] == 1
    assert slow.state is TaskState.FAILED  # the brief's "Error" status
    assert slow.pending_since is None
    assert engine.snapshot()["monitored"] is None

    recovered = [e for e in engine.events if e["event"] == "STALE_TASK_RECOVERED"]
    assert len(recovered) == 1
    entry = recovered[0]
    assert entry["task"] == "slow"
    assert entry["cycle"] == 1
    assert entry["timeout_sec"] == 300.0
    assert entry["grace_sec"] == 60.0
    assert entry["age_sec"] == pytest.approx(360.001)
    assert entry["last_progress"] == {
        "progress": 1,
        "steps_required": 10,
        "last_progress_at": start,
        "age_sec": pytest.approx(360.001),
    }

    # A recovered task is terminal: a second sweep re-recovers nothing.
    assert engine.sweep_stale_tasks(now=start + 10_000.0)["recovered"] == 0
    assert len(_stale_events(engine)) == 2


def test_stuck_progress_line_recovers_without_dead_pid_path() -> None:
    """DOD-06 / REQ-04: the brief's canonical stuck task, alongside the rest."""

    stuck = _spec("stuck", steps_required=999)
    engine, _, clock = _build([stuck], max_steps_per_cycle=1)
    engine.run_cycle()

    clock.advance(300.001)
    engine.run_cycle()
    assert [e["event"] for e in engine.events].count("STALE_TASK") == 1
    assert [e for e in engine.events if e["event"] == "FAILED"] == []
    assert engine.snapshot()["monitored"] == "stuck"

    clock.advance(360.001)
    outcome = engine.sweep_stale_tasks()
    assert outcome["recovered"] == 1
    assert stuck.state is TaskState.FAILED
    recovered = [e for e in engine.events if e["event"] == "STALE_TASK_RECOVERED"]
    assert len(recovered) == 1
    assert recovered[0]["last_progress"]["age_sec"] == pytest.approx(360.001)
    assert recovered[0]["last_progress"]["progress"] == 2

    # The exception / fingerprint path never ran for it: the new path was
    # exercised alongside the existing recovery machinery, not instead of it.
    assert engine._task_fingerprint == {}
    assert engine._buckets == {}
    assert engine.snapshot()["monitored"] is None


def test_preempted_and_quarantined_tasks_are_not_recovered() -> None:
    """DOD-07 / REQ-04: ``PENDING`` is never scanned, quarantine still closes."""

    low = _spec("low", Difficulty.EASY, steps_required=5)
    high = _spec("high", Difficulty.COMPLEX, steps_required=2, state=TaskState.CANCELLED)
    engine, curriculum, clock = _build(
        [low, high], priority_preemption_enabled=True, max_steps_per_cycle=1
    )
    assert engine.run_cycle().monitored == "low"
    curriculum.apply_state("high", TaskState.PENDING)
    assert engine.run_cycle().preempted == "low"
    assert low.state is TaskState.PENDING

    outcome = engine.sweep_stale_tasks(now=clock.now + 10_000.0)
    assert low.stale_since is None
    assert low.state is TaskState.PENDING
    assert [e for e in _stale_events(engine) if e["task"] == "low"] == []
    # The actively monitored task *is* recovered, so the scan is not inert.
    assert outcome["recovered"] == 1
    assert high.state is TaskState.FAILED

    blocked = _spec("blocked", steps_required=5)
    engine2, curriculum2, clock2 = _build(
        [blocked],
        errors={"blocked": ValueError("boom")},
        fingerprint_quarantine_threshold=1,
        fingerprint_quarantine_sec=60.0,
    )
    engine2.run_cycle()
    assert blocked.state is TaskState.FAILED
    curriculum2.apply_state("blocked", TaskState.PENDING)
    engine2.run_cycle()  # skipped: its fingerprint is quarantined
    assert blocked.state is TaskState.PENDING

    outcome = engine2.sweep_stale_tasks(now=clock2.now + 10_000.0)
    assert outcome["scanned"] == 0
    assert outcome["stale"] == 0
    assert outcome["recovered"] == 0
    assert [e for e in engine2.events if e["event"].startswith("STALE")] == []

    bucket = next(iter(engine2._buckets.values()))
    clock2.now = bucket.quarantine_until
    assert engine2.sweep_quarantine()["closed"] == 1  # untouched by the stale path


def test_scheduler_thread_sweeps_stale_tasks() -> None:
    """DOD-09 / REQ-03: the existing daemon thread carries the recovery path."""

    slow = _spec("slow", steps_required=10)
    engine, _, clock = _build([slow], quarantine_sweep_interval_sec=0.01)
    engine.run_cycle()
    clock.advance(400.0)

    thread = engine.start_scheduler(interval_sec=0.01)
    try:
        assert engine.start_scheduler() is thread  # idempotent
        assert thread.daemon is True
        deadline = time.monotonic() + 2.0
        while slow.state is not TaskState.FAILED and time.monotonic() < deadline:
            time.sleep(0.001)
        assert slow.state is TaskState.FAILED
        assert [e["event"] for e in engine.events].count("STALE_TASK_RECOVERED") == 1
    finally:
        engine.stop_scheduler()

    assert not thread.is_alive()
    engine.stop_scheduler()  # idempotent no-op


def test_exception_path_still_fails_the_task_first() -> None:
    """DOD-08 / REQ-04: a raising step keeps priority over the stale path."""

    broken = _spec("broken")
    engine, _, _ = _build([broken], errors={"broken": ValueError("boom")})
    engine.run_cycle()

    assert broken.state is TaskState.FAILED
    assert engine._task_fingerprint == {
        "broken": error_fingerprint(ValueError("boom"))
    }
    events = [event["event"] for event in engine.events]
    assert "FAILED" in events
    assert [name for name in events if name.startswith("STALE")] == []
