"""Graceful wind-down of the maintainer engine (UTILTOOLS-73).

Covers brief REQ-01 .. REQ-05 (plan DOD-01 .. DOD-13). The clock and the sleep
seam are always injected, so the 300 s default and the bounded wait are asserted
at their boundaries without a real sleep. Every persisted artifact is read back
off disk, so none of these assertions can pass on an in-memory echo.
"""

from __future__ import annotations

import json
import math
import os
from pathlib import Path

import pytest

from ai_maintainer.config import ENV_PREFIX, MaintainerConfig
from ai_maintainer.engine import CycleResult, MaintainerEngine
from ai_maintainer.shutdown import (
    CLEANUP_PERSIST_TASK_STATE,
    CLEANUP_RELEASE_FILE_LOCKS,
    CLEANUP_STOP_SCHEDULER,
    CLEANUP_WRITE_SHUTDOWN_AUDIT,
    PERSIST_SKIPPED_NO_STATE_ROOT,
    SHUTDOWN_POLL_INTERVAL_SEC,
    SHUTDOWN_STATE_FILE,
    EngineState,
    read_shutdown_state,
    write_shutdown_state,
)
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
        self.on_sleep = None

    def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)
        self.clock.advance(seconds)
        if self.on_sleep is not None:
            self.on_sleep(len(self.calls))

    @property
    def total(self) -> float:
        return sum(self.calls)


class CountingLock:
    """Minimal ``util.fs.file_lock`` stand-in: counts ``release()`` calls."""

    def __init__(self, *, fails: bool = False) -> None:
        self.releases = 0
        self.fails = fails

    def release(self) -> None:
        self.releases += 1
        if self.fails:
            raise RuntimeError("lock is not held")


def _spec(
    name: str,
    *,
    state: TaskState = TaskState.RUNNING,
    progress: int = 1,
    steps_required: int = 4,
) -> TaskSpec:
    return TaskSpec(
        name=name,
        difficulty=Difficulty.EASY,
        steps_required=steps_required,
        progress=progress,
        last_proposed_cycle=100,
        state=state,
    )


def _engine(
    tmp_path: Path,
    specs: list[TaskSpec],
    *,
    config: MaintainerConfig | None = None,
    clock: FrozenClock | None = None,
    sleeper: FakeSleeper | None = None,
    state_root: str | Path | None = "default",
    role_resolver=None,
) -> MaintainerEngine:
    clock = clock if clock is not None else FrozenClock()
    sleeper = sleeper if sleeper is not None else FakeSleeper(clock)
    cfg = config if config is not None else MaintainerConfig()
    root: Path | None
    if state_root == "default":
        root = tmp_path / "state"
    elif state_root is None:
        root = None
    else:  # pragma: no cover - explicit root, unused by these tests
        root = Path(state_root)
    curriculum = TaskCurriculum(specs=specs, config=cfg, clock=clock)
    return MaintainerEngine(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=sleeper,
        state_root=root,
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
        role_resolver=role_resolver,
    )


def _audit_lines(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    raw = path.read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in raw if line.strip()]


# --------------------------------------------------------------- REQ-01 states


def test_run_forever_polls_should_stop_and_reaches_stopped(tmp_path: Path) -> None:
    """DOD-01: stop is polled per iteration and the engine lands in STOPPED."""

    polls: list[bool] = []
    cycles: list[EngineState] = []
    observed: dict[str, object] = {}

    class Probe(MaintainerEngine):
        def should_stop(self) -> bool:
            polls.append(True)
            return super().should_stop()

        def run_cycle(self) -> CycleResult:
            cycles.append(self.state)
            result = super().run_cycle()
            if len(cycles) >= 2:
                self.request_stop()
            return result

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=1)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    engine = Probe(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )

    def _during_cleanup() -> None:
        observed["state"] = engine.state
        observed["shutting_down"] = engine.is_shutting_down()

    engine.stop_scheduler = _during_cleanup  # observed at the first cleanup step

    assert {state.value for state in EngineState} == {
        "IDLE",
        "SLEEPING",
        "RUNNING",
        "SHUTTING_DOWN",
        "STOPPED",
    }
    report = engine.run_forever(interval_sec=0.0)

    # polled at least once per iteration plus the pre-shutdown check
    assert len(polls) >= len(cycles) + 1
    assert len(cycles) == 2
    assert all(state is EngineState.RUNNING for state in cycles)
    assert observed["state"] is EngineState.SHUTTING_DOWN
    assert observed["shutting_down"] is True
    assert report.state is EngineState.STOPPED
    assert engine.state is EngineState.STOPPED
    assert engine.should_stop() is True


def test_run_forever_cycles_until_stop(tmp_path: Path) -> None:
    """DOD-02: cycles run freely until the stop signal, IDLE/SLEEPING between."""

    between: list[EngineState] = []

    class Probe(MaintainerEngine):
        def run_cycle(self) -> CycleResult:
            result = super().run_cycle()
            if len(between) >= 3:
                self.request_stop()
            return result

        def _pause(self, seconds: float) -> bool:
            between.append(self.state)
            return True

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=1, max_shutdown_wait_sec=5.0)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    engine = Probe(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )

    report = engine.run_forever(interval_sec=0.25)

    assert len(between) == 3
    assert all(state is EngineState.SLEEPING for state in between)
    assert report.timed_out is False
    assert engine.state is EngineState.STOPPED
    assert curriculum.specs[0].progress > 1


# ------------------------------------------------------------------ REQ-02 bound


def test_max_shutdown_wait_sec_default_env_and_validation(tmp_path: Path) -> None:
    """DOD-03: default 300 s, env coercion, validation, and loop read-out."""

    assert MaintainerConfig().max_shutdown_wait_sec == 300.0

    config = MaintainerConfig.load(
        maintainer_root=tmp_path,
        env={ENV_PREFIX + "MAX_SHUTDOWN_WAIT_SEC": "12.5"},
    )
    assert config.max_shutdown_wait_sec == 12.5

    aliased = MaintainerConfig.load(
        maintainer_root=tmp_path,
        env={"AI_" + ENV_PREFIX + "MAX_SHUTDOWN_WAIT_SEC": "7"},
    )
    assert aliased.max_shutdown_wait_sec == 7.0

    with pytest.raises(ValueError):
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={ENV_PREFIX + "MAX_SHUTDOWN_WAIT_SEC": "-1"},
        )
    with pytest.raises(ValueError):
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={ENV_PREFIX + "MAX_SHUTDOWN_WAIT_SEC": "inf"},
        )
    with pytest.raises(ValueError):
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={ENV_PREFIX + "MAX_SHUTDOWN_WAIT_SEC": "nan"},
        )

    # The wait loop reads exactly ``self.config.max_shutdown_wait_sec``: the env
    # layer reaches the engine through the config, and a break point that never
    # arrives stops the loop after that many injected waits.
    clock = FrozenClock()
    sleeper = FakeSleeper(clock)
    bounded = MaintainerConfig.load(
        maintainer_root=tmp_path,
        env={ENV_PREFIX + "MAX_SHUTDOWN_WAIT_SEC": "0.5"},
    )
    engine = _engine(
        tmp_path, [_spec("task-a")], config=bounded, clock=clock, sleeper=sleeper
    )
    assert engine.config.max_shutdown_wait_sec == 0.5

    engine._cycle_done.clear()  # a step that never reaches its break point
    report = engine.shutdown()

    assert report.timed_out is True
    assert report.waited_sec == pytest.approx(0.5)
    assert sleeper.total <= 0.5 + SHUTDOWN_POLL_INTERVAL_SEC
    assert all(wait <= SHUTDOWN_POLL_INTERVAL_SEC for wait in sleeper.calls)


# ------------------------------------------------------------ REQ-03 persistence


def test_shutdown_state_file_written_per_running_task_slot(tmp_path: Path) -> None:
    """DOD-04: one ``_shutdown_state.json`` per in-flight slot, read off disk."""

    state_root = tmp_path / "state"
    engine = _engine(
        tmp_path,
        [
            _spec("task-a"),
            _spec("task-b", state=TaskState.IN_PROGRESS, progress=2),
            _spec("task-idle", state=TaskState.PENDING),
        ],
    )
    engine.note_task_role("task-a", "Developer")

    report = engine.shutdown()

    assert report.persisted_task_ids == ("task-a", "task-b")
    document = json.loads(
        (state_root / "task-a" / SHUTDOWN_STATE_FILE).read_text(encoding="utf-8")
    )
    for key in ("role", "status", "timeline", "elapsed_shutdown_wait_sec"):
        assert key in document
    assert document["role"] == "Developer"
    assert document["role_source"] == "engine"
    assert document["status"] == TaskState.RUNNING.value
    assert isinstance(document["timeline"], dict)
    assert document["elapsed_shutdown_wait_sec"] >= 0.0
    other = read_shutdown_state(state_root / "task-b")
    assert other is not None and other["status"] == TaskState.IN_PROGRESS.value
    assert not (state_root / "task-idle").exists()


def test_shutdown_state_restores_role_and_status_after_restart(tmp_path: Path) -> None:
    """DOD-05: role/status survive a restart; absent evidence stays ``None``."""

    state_root = tmp_path / "state"
    engine = _engine(
        tmp_path,
        [_spec("task-a"), _spec("task-b")],
        role_resolver=lambda task: "System Designer" if task.name == "task-b" else None,
    )
    engine.note_task_role("task-a", "Developer")
    engine.shutdown()

    # A fresh process reads the slot documents back with no engine in memory.
    restored_a = read_shutdown_state(state_root / "task-a")
    restored_b = read_shutdown_state(state_root / "task-b")
    assert restored_a is not None and restored_b is not None
    assert (restored_a["role"], restored_a["status"]) == (
        "Developer",
        TaskState.RUNNING.value,
    )
    assert (restored_b["role"], restored_b["status"]) == (
        "System Designer",
        TaskState.RUNNING.value,
    )

    unobserved = _engine(tmp_path / "other", [_spec("task-c")])
    unobserved.shutdown()
    document = read_shutdown_state(tmp_path / "other" / "state" / "task-c")
    assert document is not None
    assert document["role"] is None
    assert document["role_source"] == "unobserved"

    # A truncated document is evidence of nothing, never a guess.
    slot = tmp_path / "truncated"
    slot.mkdir()
    (slot / SHUTDOWN_STATE_FILE).write_text('{"role": "Dev', encoding="utf-8")
    assert read_shutdown_state(slot) is None
    assert read_shutdown_state(tmp_path / "missing-slot") is None


def test_shutdown_state_write_refused_for_symlinked_slot(tmp_path: Path) -> None:
    """DOD-06 (INV-SHUT-3): a symlinked slot never receives the document."""

    state_root = tmp_path / "state"
    outside = tmp_path / "outside"
    state_root.mkdir()
    outside.mkdir()
    os.symlink(outside, state_root / "task-a")
    engine = _engine(tmp_path, [_spec("task-a")])

    report = engine.shutdown()

    assert report.persisted_task_ids == ()
    assert CLEANUP_PERSIST_TASK_STATE in report.failed_steps
    assert not (outside / SHUTDOWN_STATE_FILE).exists()
    assert not (state_root / SHUTDOWN_STATE_FILE).exists()
    assert read_shutdown_state(outside) is None


# ---------------------------------------------------------------- REQ-04 locks


def test_all_tracked_file_locks_released_once(tmp_path: Path) -> None:
    """DOD-07: every registered lock is released exactly once."""

    engine = _engine(tmp_path, [_spec("task-a")])
    locks = {name: CountingLock() for name in ("state", "progress", "timeline")}
    for key, lock in locks.items():
        engine.register_file_lock(key, lock)
    assert engine.file_locks == ("state", "progress", "timeline")

    report = engine.shutdown()

    assert engine.file_locks == ()
    assert all(lock.releases == 1 for lock in locks.values())
    assert set(report.released_lock_keys) == set(locks)
    assert report.lock_release_failures == ()
    assert CLEANUP_RELEASE_FILE_LOCKS in report.cleanup_steps


def test_release_failure_does_not_strand_remaining_locks(tmp_path: Path) -> None:
    """DOD-08 (INV-SHUT-6): a raising ``release()`` strands no other lock."""

    engine = _engine(tmp_path, [_spec("task-a")])
    broken = CountingLock(fails=True)
    healthy = {name: CountingLock() for name in ("b", "c")}
    engine.register_file_lock("a", broken)
    for key, lock in healthy.items():
        engine.register_file_lock(key, lock)

    report = engine.shutdown()

    assert engine.file_locks == ()
    assert report.lock_release_failures == ("a",)
    assert set(report.released_lock_keys) == set(healthy)
    assert all(lock.releases == 1 for lock in healthy.values())
    assert broken.releases == 1
    assert CLEANUP_RELEASE_FILE_LOCKS in report.failed_steps
    assert CLEANUP_RELEASE_FILE_LOCKS not in report.cleanup_steps


# ---------------------------------------------------------------- REQ-05 audit


def test_audit_record_written_with_required_fields(tmp_path: Path) -> None:
    """DOD-09: exactly one JSON line carrying the four contract fields."""

    engine = _engine(tmp_path, [_spec("task-a"), _spec("task-b")])
    report = engine.shutdown()

    audit_path = Path(str(report.audit_path))
    assert audit_path == tmp_path / "data" / "maintainer_shutdown.log"
    lines = _audit_lines(audit_path)
    assert len(lines) == 1
    record = lines[0]
    assert set(("recorded_at", "active_task_ids", "cleanup_steps", "timed_out")) <= set(
        record
    )
    assert record["active_task_ids"] == ["task-a", "task-b"]
    assert record["timed_out"] is False
    assert isinstance(record["recorded_at"], float)
    assert CLEANUP_STOP_SCHEDULER in record["cleanup_steps"]
    assert CLEANUP_WRITE_SHUTDOWN_AUDIT in record["cleanup_steps"]
    assert report.audit_written is True


def test_wait_loop_reaches_natural_break_point_within_timeout(
    tmp_path: Path,
) -> None:
    """DOD-10: the in-flight cycle checkpoints inside the bound."""

    clock = FrozenClock()
    sleeper = FakeSleeper(clock)
    engine = _engine(
        tmp_path,
        [_spec("task-a")],
        clock=clock,
        sleeper=sleeper,
        config=MaintainerConfig(max_shutdown_wait_sec=30.0),
    )
    engine._cycle_done.clear()
    sleeper.on_sleep = lambda _count: engine._cycle_done.set()

    report = engine.shutdown()

    assert report.timed_out is False
    assert 0.0 <= report.waited_sec <= 30.0 + SHUTDOWN_POLL_INTERVAL_SEC
    assert report.waited_sec > 0.0
    assert sleeper.total <= 30.0 + SHUTDOWN_POLL_INTERVAL_SEC
    record = _audit_lines(Path(str(report.audit_path)))[-1]
    assert record["timed_out"] is False


def test_shutdown_delegates_to_monitor_until_terminal(tmp_path: Path) -> None:
    """UTILTOOLS-89 REQ-01: one monitoring helper, reused by ``shutdown()``.

    The wind-down wait must stay the single ``_monitor_until_terminal`` loop so a
    stop observed there is never re-implemented (and never drifts) per caller.
    """

    clock = FrozenClock()
    engine = _engine(
        tmp_path,
        [_spec("task-a")],
        clock=clock,
        sleeper=FakeSleeper(clock),
        config=MaintainerConfig(max_shutdown_wait_sec=5.0),
    )
    engine._cycle_done.clear()

    calls: list[tuple[float | None, float | None, bool]] = []
    real = engine._monitor_until_terminal

    def _spy(
        poll_interval_sec: float | None = None,
        *,
        timeout_sec: float | None = None,
        honour_stop: bool = True,
    ) -> str:
        calls.append((poll_interval_sec, timeout_sec, honour_stop))
        return real(poll_interval_sec, timeout_sec=timeout_sec, honour_stop=honour_stop)

    engine._monitor_until_terminal = _spy  # type: ignore[method-assign]

    engine.shutdown()

    assert len(calls) == 1
    poll_interval_sec, timeout_sec, honour_stop = calls[0]
    assert poll_interval_sec is None  # helper supplies SHUTDOWN_POLL_INTERVAL_SEC
    assert timeout_sec == 5.0
    # The drain waits for an *already published* stop: honour_stop=False.
    assert honour_stop is False


def test_wait_loop_times_out_at_max_shutdown_wait_sec(tmp_path: Path) -> None:
    """DOD-11: a break point that never arrives stops at the configured bound."""

    clock = FrozenClock()
    sleeper = FakeSleeper(clock)
    engine = _engine(
        tmp_path,
        [_spec("task-a")],
        clock=clock,
        sleeper=sleeper,
        config=MaintainerConfig(max_shutdown_wait_sec=1.0),
    )
    engine._cycle_done.clear()  # the cycle never reaches a break point

    report = engine.shutdown()

    expected_injections = math.ceil(1.0 / SHUTDOWN_POLL_INTERVAL_SEC)
    assert report.timed_out is True
    assert report.waited_sec == pytest.approx(1.0)
    # one poll interval of rounding slack at the boundary
    assert expected_injections <= len(sleeper.calls) <= expected_injections + 1
    record = _audit_lines(Path(str(report.audit_path)))[-1]
    assert record["timed_out"] is True
    assert record["max_shutdown_wait_sec"] == pytest.approx(1.0)


def test_second_shutdown_is_idempotent_single_audit_line(tmp_path: Path) -> None:
    """DOD-12 (INV-SHUT-2): the memoised report appends no second line."""

    engine = _engine(tmp_path, [_spec("task-a")])
    first = engine.shutdown()
    second = engine.shutdown()

    assert first is second
    assert len(_audit_lines(Path(str(first.audit_path)))) == 1
    assert engine.state is EngineState.STOPPED


# ------------------------------------------------------------- restart hygiene


def test_cycle_after_shutdown_starts_cleanly(tmp_path: Path) -> None:
    """DOD-13 (INV-SHUT-5): STOPPED is a clean slate for the next run."""

    poll_states: list[EngineState] = []

    class Probe(MaintainerEngine):
        def should_stop(self) -> bool:
            poll_states.append(self.state)
            return super().should_stop()

        def run_cycle(self) -> CycleResult:
            result = super().run_cycle()
            self.request_stop()
            return result

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=1)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    engine = Probe(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )

    engine.shutdown()
    assert engine.state is EngineState.STOPPED
    events_before = len(engine.events)

    result = engine.run_cycle()
    assert isinstance(result, CycleResult)
    assert result.steps >= 1
    new_events = [event["event"] for event in engine.events[events_before:]]
    # A fresh cycle emits its cycle-state lifecycle events (UTILTOOLS-111);
    # those are the restart trace itself, so tolerate them alongside
    # STOP_REQUESTED.
    assert [
        name for name in new_events
        if name not in ("STOP_REQUESTED", "CYCLE_STATE_TRANSITION")
    ] == []

    report = engine.run_forever(interval_sec=0.0)
    assert poll_states[0] is EngineState.IDLE  # re-entered from IDLE
    assert report.state is EngineState.STOPPED
    assert CLEANUP_STOP_SCHEDULER in report.cleanup_steps


def test_persist_skipped_without_state_root(tmp_path: Path) -> None:
    """REQ-03 guard: no state root means an explicit skip, never a crash."""

    engine = _engine(tmp_path, [_spec("task-a")], state_root=None)
    report = engine.shutdown()

    assert report.persisted_task_ids == ()
    assert report.persist_skipped_reason == PERSIST_SKIPPED_NO_STATE_ROOT
    assert report.state is EngineState.STOPPED


def test_write_shutdown_state_roundtrip(tmp_path: Path) -> None:
    """REQ-03 seam: the writer/reader pair is atomic and root-guarded."""

    root = tmp_path / "state"
    target = write_shutdown_state(
        root / "task-x", root, {"task": "task-x", "role": "Developer", "status": "RUNNING"}
    )

    assert target == root / "task-x" / SHUTDOWN_STATE_FILE
    assert not (root / "task-x" / (SHUTDOWN_STATE_FILE + ".tmp")).exists()
    document = read_shutdown_state(root / "task-x")
    assert document == {"task": "task-x", "role": "Developer", "status": "RUNNING"}

    with pytest.raises(OSError):
        write_shutdown_state(tmp_path / "elsewhere", root, {"task": "x"})
    with pytest.raises(ValueError):
        write_shutdown_state(root / "task-y", root, ["not", "a", "mapping"])
