"""Cooperative task cancellation and graceful finalization (UTILTOOLS-89).

Covers brief REQ-01 .. REQ-04 and plan DOD-01 .. DOD-12. Every assertion reads a
returned token, a recorded event or a file on disk - never an in-memory echo of
the call under test. The poll loop is exercised both with injected ``clock`` /
``sleep`` seams (deterministic latency accounting) and with real threads (the
"leave the in-flight cycle within one poll interval" property is a concurrency
property, so it needs a real stop signal and a real timer).
"""

from __future__ import annotations

import signal
import threading
import time
from pathlib import Path
from typing import Any

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import (
    FINALIZED_EVENT,
    FORCE_STOP_EVENT,
    MONITOR_STOPPED,
    MONITOR_TERMINAL,
    MONITOR_TIMEOUT,
    OUTCOME_FAILED,
    OUTCOME_INTERRUPTED,
    OUTCOME_SUCCESS,
    OUTCOME_TIMEOUT,
    RUNTIME_HEARTBEAT_FILE,
    RUNTIME_MARKER_TMP_SUFFIX,
    RUNTIME_PID_FILE,
    STOP_REASON_FAILED,
    STOP_REASON_STOPPED,
    STOP_REASON_TIMEOUT,
    CooperativeCancel,
    CycleResult,
    MaintainerEngine,
)
from ai_maintainer.tasks.curriculum import (
    Difficulty,
    TaskCurriculum,
    TaskSpec,
    TaskState,
)


# --------------------------------------------------------------- test fixtures


class FrozenClock:
    """Clock that only moves when the fake sleeper moves it."""

    def __init__(self, now: float = 1_000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> float:
        self.now += seconds
        return self.now


class FakeSleeper:
    """``time.sleep`` stand-in: records each wait, moves the clock, then runs
    an optional ``on_sleep`` hook (used to publish a stop *during* a wait)."""

    def __init__(self, clock: FrozenClock) -> None:
        self.clock = clock
        self.calls: list[float] = []
        self.on_sleep: Any = None

    def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)
        self.clock.advance(seconds)
        hook, self.on_sleep = self.on_sleep, None
        if hook is not None:
            hook()


def _spec(name: str = "task-a", *, steps_required: int = 4) -> TaskSpec:
    return TaskSpec(
        name=name,
        difficulty=Difficulty.EASY,
        steps_required=steps_required,
        progress=1,
        last_proposed_cycle=100,
        state=TaskState.RUNNING,
    )


def _config(**kwargs: Any) -> MaintainerConfig:
    params: dict[str, Any] = {"max_steps_per_cycle": 3}
    params.update(kwargs)
    return MaintainerConfig(**params)


def _audit_path(tmp_path: Path) -> Path:
    audit = tmp_path / "data" / "maintainer_shutdown.log"
    audit.parent.mkdir(parents=True, exist_ok=True)
    return audit


def _frozen_engine(
    tmp_path: Path,
    *,
    cls: type[MaintainerEngine] = MaintainerEngine,
    specs: list[TaskSpec] | None = None,
    config: MaintainerConfig | None = None,
) -> tuple[MaintainerEngine, FakeSleeper]:
    """Engine with injected clock/sleep: one "poll" costs nothing in wall time."""

    cfg = config if config is not None else _config()
    clock = FrozenClock()
    sleeper = FakeSleeper(clock)
    engine = cls(
        TaskCurriculum(
            specs=specs if specs is not None else [_spec()], config=cfg, clock=clock
        ),
        cfg,
        clock=clock,
        sleep=sleeper,
        state_root=tmp_path / "state",
        shutdown_audit_path=_audit_path(tmp_path),
    )
    return engine, sleeper


def _live_engine(
    tmp_path: Path,
    *,
    cls: type[MaintainerEngine] = MaintainerEngine,
    specs: list[TaskSpec] | None = None,
) -> MaintainerEngine:
    """Engine on the real clock/sleep: the only honest way to time a poll."""

    cfg = _config()
    return cls(
        TaskCurriculum(specs=specs if specs is not None else [_spec()], config=cfg),
        cfg,
        state_root=tmp_path / "state",
        shutdown_audit_path=_audit_path(tmp_path),
    )


def _gated_engine(
    tmp_path: Path,
    *,
    cls: type[MaintainerEngine] | None = None,
) -> MaintainerEngine:
    """Live-clock engine whose step blocks until the test opens the gate.

    The step deliberately never looks at ``check_cancel()`` so the coercion
    path (DOD-07) has something genuinely non-cooperative to book-keep.
    """

    class Gated(cls or MaintainerEngine):  # type: ignore[misc, valid-type]
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            super().__init__(*args, **kwargs)
            self.step_started = threading.Event()
            self.release = threading.Event()
            self.seen: TaskSpec | None = None

        def _monitor_step(self, task: TaskSpec) -> None:
            self.seen = task
            self.step_started.set()
            self.release.wait(10.0)

    cfg = _config()
    return Gated(
        TaskCurriculum(specs=[_spec()], config=cfg),
        cfg,
        state_root=tmp_path / "state",
        shutdown_audit_path=_audit_path(tmp_path),
    )


def _start_cycle(engine: MaintainerEngine) -> tuple[threading.Thread, list[BaseException]]:
    errors: list[BaseException] = []
    results: list[CycleResult] = []

    def body() -> None:
        try:
            results.append(engine.run_cycle())
        except BaseException as exc:  # pragma: no cover - surfaced by the test
            errors.append(exc)

    thread = threading.Thread(target=body, daemon=True)
    thread.start()
    return thread, errors


def _events(engine: MaintainerEngine, name: str) -> list[dict]:
    return [event for event in engine.events if event.get("event") == name]


def _marker_names(tmp_path: Path) -> list[str]:
    """Only the two runtime markers and their ``.tmp`` siblings, not leases."""

    state = tmp_path / "state"
    if not state.is_dir():
        return []
    markers = {RUNTIME_PID_FILE, RUNTIME_HEARTBEAT_FILE}
    return sorted(
        path.name
        for path in state.iterdir()
        if path.name in markers or path.name.endswith(RUNTIME_MARKER_TMP_SUFFIX)
    )


# ------------------------------------------- REQ-01 / DOD-01 .. DOD-04: monitor


def test_monitor_until_terminal_returns_stopped_within_poll_interval(
    tmp_path: Path,
) -> None:
    """DOD-01: a stop is observed inside one poll interval of a live loop."""

    engine = _live_engine(tmp_path)
    engine._cycle_done.clear()  # no cycle has run: nothing is terminal yet

    stopper = threading.Thread(target=lambda: (time.sleep(0.05), engine.request_stop()))
    stopper.start()
    started = time.monotonic()
    status = engine._monitor_until_terminal(1.0, timeout_sec=5.0)
    elapsed = time.monotonic() - started
    stopper.join(5.0)

    assert status == MONITOR_STOPPED == "stopped"
    assert elapsed < 5.0  # the bound was never reached
    assert elapsed < 1.5  # one interval plus scheduling slack, not a full cycle


def test_monitor_until_terminal_stop_latency_is_one_interval(tmp_path: Path) -> None:
    """DOD-02: exactly one injected poll happens before the loop leaves."""

    engine, sleeper = _frozen_engine(tmp_path)
    engine._cycle_done.clear()
    sleeper.on_sleep = engine.request_stop  # the stop lands *during* the first poll

    started = engine._clock()
    status = engine._monitor_until_terminal(1.0, timeout_sec=30.0)

    assert status == MONITOR_STOPPED
    assert sleeper.calls == [1.0]  # one poll, then out
    assert engine._clock() - started == pytest.approx(1.0)


def test_monitor_until_terminal_returns_terminal_on_cycle_done(tmp_path: Path) -> None:
    """DOD-03: a finished cycle is terminal even with a stop pending."""

    engine, sleeper = _frozen_engine(tmp_path)
    engine._cycle_done.set()
    engine.request_stop()

    assert engine._monitor_until_terminal(1.0, timeout_sec=5.0) == MONITOR_TERMINAL
    assert sleeper.calls == []  # returns before polling at all
    deadline = engine._clock() + 5.0
    assert engine._await_cycle_break_point(deadline) is False


def test_monitor_until_terminal_timeout_token(tmp_path: Path) -> None:
    """DOD-04: the bound is a ``timeout`` token, and ``cancel_task`` delegates."""

    engine, sleeper = _frozen_engine(tmp_path)
    engine._cycle_done.clear()

    assert engine._monitor_until_terminal(1.0, timeout_sec=2.0) == MONITOR_TIMEOUT
    assert sleeper.calls == [1.0, 1.0]
    assert engine.should_stop() is False  # a timeout is not a stop

    # Delegation: the very same loop is what ``cancel_task()`` waits on, and a
    # cycle that never leaves keeps the timeout honest there too.
    live = _gated_engine(tmp_path / "cancel")
    thread, errors = _start_cycle(live)
    assert live.step_started.wait(10.0)
    report = live.cancel_task(timeout_sec=0.2)
    live.release.set()
    thread.join(10.0)

    assert report.cancelled is True
    assert report.timed_out is True
    assert report.waited_sec >= 0.2
    assert errors == []


# ------------------------------------- REQ-02 / DOD-05 .. DOD-07: cooperative


class _PollingEngine(MaintainerEngine):
    """Step that polls the cooperative signal and unwinds on purpose."""

    def _monitor_step(self, task: TaskSpec) -> None:
        self.request_stop()
        if self.check_cancel():  # REQ-02: the documented hint, not an exception
            raise CooperativeCancel(self.check_cancel_reason())


def test_step_observes_cancel_check_and_finalizes(tmp_path: Path) -> None:
    """DOD-05: a cooperative step ends the cycle ``INTERRUPTED``, not FAILED."""

    engine, _ = _frozen_engine(tmp_path, cls=_PollingEngine)
    task = engine._curriculum.specs[0]

    result = engine.run_cycle()

    assert isinstance(result, CycleResult)
    assert result.outcome == OUTCOME_INTERRUPTED == "INTERRUPTED"
    assert result.outcome not in (OUTCOME_FAILED, OUTCOME_TIMEOUT)
    assert result.interrupted_by == "STOP"
    assert task.state is TaskState.CANCELLED
    finalized = _events(engine, FINALIZED_EVENT)
    assert len(finalized) == 1
    assert finalized[0]["task"] == "task-a"
    assert _events(engine, "BLOCKED") == []  # never tallied as a failure


class _RaisingEngine(MaintainerEngine):
    """Step that raises :class:`CooperativeCancel` directly."""

    def _monitor_step(self, task: TaskSpec) -> None:
        self.request_stop()
        raise CooperativeCancel(STOP_REASON_STOPPED)


def test_cooperative_cancel_exception_maps_to_interrupted(tmp_path: Path) -> None:
    """DOD-06: the exception carries the reason and is never a failure."""

    engine, _ = _frozen_engine(tmp_path, cls=_RaisingEngine)

    result = engine.run_cycle()

    assert result.outcome == OUTCOME_INTERRUPTED
    assert result.to_dict()["outcome"] == OUTCOME_INTERRUPTED
    assert result.interrupted_by == "STOP"
    finalized = _events(engine, FINALIZED_EVENT)
    assert len(finalized) == 1
    assert finalized[0]["reason"] == STOP_REASON_STOPPED
    # No error-fingerprint tally, no quarantine circuit: a cooperative stop is
    # an interruption by construction.
    assert _events(engine, "BLOCKED") == []


def test_uncooperative_task_force_marked_stopped(tmp_path: Path) -> None:
    """DOD-07: a task that ignores the signal is coerced in bookkeeping only."""

    engine = _gated_engine(tmp_path)
    thread, errors = _start_cycle(engine)
    assert engine.step_started.wait(10.0)

    engine.request_stop()
    started = time.monotonic()
    status = engine._monitor_until_terminal(0.02, timeout_sec=0.15, honour_stop=False)
    elapsed = time.monotonic() - started

    assert status == MONITOR_STOPPED == "stopped"
    assert elapsed >= 0.15  # the cooperative window was really waited out
    assert thread.is_alive() is True  # coercion is bookkeeping, not termination
    coerced = _events(engine, FORCE_STOP_EVENT)
    assert len(coerced) == 1  # exactly once, never duplicated
    assert coerced[0]["task"] == "task-a"
    assert coerced[0]["waited_sec"] >= 0.15
    seen = getattr(engine, "seen", None)
    assert seen is not None and seen.state is TaskState.CANCELLED

    engine.release.set()
    thread.join(10.0)
    assert errors == []


# ------------------------------- REQ-03 / DOD-08 .. DOD-10: cleanup guarantees


class _StopsInStepEngine(MaintainerEngine):
    """Step that publishes a stop from inside the running cycle."""

    def _monitor_step(self, task: TaskSpec) -> None:
        self.request_stop()


def test_run_forever_mid_cycle_stop_leaves_no_runtime_markers(tmp_path: Path) -> None:
    """DOD-08: stopping mid-cycle still unlinks pid/heartbeat (and .tmp siblings)."""

    engine = _gated_engine(tmp_path)
    reports: list[Any] = []

    def body() -> None:
        reports.append(engine.run_forever(0.01, install_signals=False))

    runner = threading.Thread(target=body, daemon=True)
    runner.start()
    assert engine.step_started.wait(10.0)
    heartbeat = engine.heartbeat_file
    assert heartbeat is not None and heartbeat.exists() is True  # markers are live

    engine.request_stop()
    engine.release.set()
    runner.join(10.0)

    assert runner.is_alive() is False
    assert reports and reports[0] is not None
    assert _marker_names(tmp_path) == []


class _ExplodingShutdownEngine(_StopsInStepEngine):
    """Wind-down that raises: the ``finally`` cleanup must still run."""

    def graceful_shutdown(self, *, exit_process: bool = False) -> Any:  # type: ignore[override]
        raise RuntimeError("graceful boom")


def test_runtime_markers_cleared_when_graceful_shutdown_raises(
    tmp_path: Path,
) -> None:
    """DOD-09: marker unlink cannot be skipped by a raising wind-down."""

    engine, _ = _frozen_engine(tmp_path, cls=_ExplodingShutdownEngine)

    with pytest.raises(RuntimeError):
        engine.run_forever(0.01, install_signals=False)

    assert _marker_names(tmp_path) == []


class _SignallingEngine(MaintainerEngine):
    """Step that delivers SIGTERM the way the installed handler would."""

    def _monitor_step(self, task: TaskSpec) -> None:
        self._handle_signal(signal.SIGTERM, None)


def test_sigterm_during_run_once_cleans_markers(tmp_path: Path) -> None:
    """DOD-10: a signal mid-cycle winds the run down and leaves nothing behind."""

    engine, _ = _frozen_engine(tmp_path, cls=_SignallingEngine)

    report = engine.run_forever(0.01, install_signals=False)

    assert report is not None
    assert engine.should_stop() is True
    assert engine.stop_reason == "SIGNAL_SIGTERM"
    published = _events(engine, "STOP_REQUESTED")
    assert len(published) == 1
    assert published[0]["reason"] == "SIGNAL_SIGTERM"
    assert _marker_names(tmp_path) == []


# --------------------------------- REQ-04 / DOD-11 .. DOD-12: one classifier


@pytest.mark.parametrize(
    "reason",
    ["stop_requested", "cancel_requested", "SIGNAL_SIGTERM", "SIGNAL_SIGINT"],
)
def test_outcome_for_maps_stop_reasons_to_interrupted(reason: str) -> None:
    """DOD-11: the coercion token and every interruption map to INTERRUPTED.

    ``interrupted`` is the caller's "the stop ended the cycle before it could
    commit" statement, so the stop-family reasons classify through it. A stop
    seen *after* a committed step deliberately stays ``SUCCESS`` (UTILTOOLS-80
    REQ-02), which is why the bare reason alone is not enough.
    """

    assert MaintainerEngine._outcome_for(STOP_REASON_STOPPED) == OUTCOME_INTERRUPTED
    assert (
        MaintainerEngine._outcome_for(reason, interrupted=True) == OUTCOME_INTERRUPTED
    )
    assert MaintainerEngine._outcome_for(reason) == OUTCOME_SUCCESS
    # Total by construction: junk input classifies instead of raising.
    for junk in (None, "", 42, object(), [], {"reason": reason}):
        assert MaintainerEngine._outcome_for(junk) in {
            OUTCOME_SUCCESS,
            OUTCOME_INTERRUPTED,
            OUTCOME_FAILED,
            OUTCOME_TIMEOUT,
        }


def test_outcome_for_preserves_failed_and_timeout() -> None:
    """DOD-12: a genuine failure/timeout is never laundered as an interruption."""

    assert MaintainerEngine._outcome_for(STOP_REASON_FAILED) == OUTCOME_FAILED
    assert MaintainerEngine._outcome_for("failed") == "FAILED"
    assert MaintainerEngine._outcome_for(STOP_REASON_TIMEOUT) == OUTCOME_TIMEOUT
    assert MaintainerEngine._outcome_for("timeout") == "TIMEOUT"
    assert MaintainerEngine._outcome_for("completed") == OUTCOME_SUCCESS
    # ``interrupted`` wins over every reason (UTILTOOLS-80 attribution).
    assert MaintainerEngine._outcome_for(STOP_REASON_FAILED, interrupted=True) == (
        OUTCOME_INTERRUPTED
    )
