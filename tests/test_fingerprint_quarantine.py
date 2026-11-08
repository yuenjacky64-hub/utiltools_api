"""Per-fingerprint circuit breaking and quarantine for the maintainer engine.

Covers brief REQ-01 .. REQ-03 (DOD-01 .. DOD-08 and DOD-12 in the plan). The
clock is always injected, so a 60 s quarantine window is asserted at its
boundary without sleeping, and the monitor step is scripted through the
existing ``_monitor_step`` seam so no production seam had to change.
"""

from __future__ import annotations

import threading
import time

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import (
    FingerprintBucket,
    FingerprintCircuitState,
    MaintainerEngine,
    error_fingerprint,
)
from ai_maintainer.tasks.curriculum import Difficulty, TaskCurriculum, TaskSpec, TaskState

VALUE_ERROR = "builtins.ValueError"
TIMEOUT_ERROR = "builtins.TimeoutError"


class ErrorA(Exception):
    """Distinct fingerprint #1 for the bounded-registry test."""


class ErrorB(Exception):
    """Distinct fingerprint #2 for the bounded-registry test."""


class ErrorC(Exception):
    """Distinct fingerprint #3 for the bounded-registry test."""


class ErrorD(Exception):
    """Distinct fingerprint #4 for the bounded-registry test."""


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
    """Engine whose monitor step raises the scripted error of a task, if any."""

    def __init__(self, *args, errors=None, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.errors: dict[str, BaseException] = dict(errors or {})
        self.stepped: list[str] = []

    def _monitor_step(self, task: TaskSpec) -> None:
        self.stepped.append(task.name)
        error = self.errors.get(task.name)
        if error is not None:
            raise error
        return None


def _build(
    specs: list[TaskSpec],
    *,
    errors: dict[str, BaseException] | None = None,
    probe=None,
    clock: FrozenClock | None = None,
    **config_kwargs,
) -> tuple[ScriptedEngine, TaskCurriculum, FrozenClock]:
    clock = clock if clock is not None else FrozenClock(100.0)
    config_kwargs.setdefault("max_steps_per_cycle", 8)
    config = MaintainerConfig(**config_kwargs)
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    engine = ScriptedEngine(
        curriculum, config=config, clock=clock, probe=probe, errors=errors
    )
    return engine, curriculum, clock


def _failing(names: list[str], error: BaseException) -> dict[str, BaseException]:
    return {name: error for name in names}


def test_bucket_defaults() -> None:
    """DOD-01 / REQ-01: the bucket exposes the key, counter, timer and state."""

    bucket = FingerprintBucket(fingerprint=VALUE_ERROR)
    assert bucket.fingerprint == VALUE_ERROR
    assert bucket.consecutive_blocked == 0
    assert bucket.quarantine_until is None
    assert bucket.circuit_state is FingerprintCircuitState.CLOSED
    assert bucket.is_quarantined(0.0) is False
    assert bucket.remaining_sec(0.0) == 0.0
    assert bucket.to_dict(now=0.0) == {
        "fingerprint": VALUE_ERROR,
        "circuit_state": "CLOSED",
        "consecutive_blocked": 0,
        "quarantine_active": False,
        "quarantine_until": None,
        "remaining_sec": 0.0,
        "last_cycle": 0,
    }

    # The fingerprint is the exception *type*, never its message.
    assert error_fingerprint(ValueError("secret payload")) == VALUE_ERROR
    assert error_fingerprint(TimeoutError("other payload")) == TIMEOUT_ERROR
    from ai_maintainer import engine as engine_module

    assert engine_module.FingerprintBucket is FingerprintBucket


def test_consecutive_blocked_is_per_fingerprint() -> None:
    """DOD-02 / REQ-02: two error types never share one counter."""

    engine, _, clock = _build(
        [_spec("a"), _spec("b")],
        errors={"a": ValueError("boom"), "b": TimeoutError("slow")},
        fingerprint_quarantine_threshold=2,
    )
    clock.advance(1.0)
    result = engine.run_cycle()
    assert result.steps == 2

    state = engine.fingerprint_state()
    assert state["quarantined_count"] == 0
    buckets = {item["fingerprint"]: item for item in state["fingerprints"]}
    assert set(buckets) == {VALUE_ERROR, TIMEOUT_ERROR}
    assert buckets[VALUE_ERROR]["consecutive_blocked"] == 1
    assert buckets[TIMEOUT_ERROR]["consecutive_blocked"] == 1
    assert all(item["circuit_state"] == "CLOSED" for item in buckets.values())
    assert [event["event"] for event in engine.events].count("FAILED") == 2
    assert engine.events[0] == {"event": "FAILED", "task": "a", "cycle": 1}


def test_blocked_backoff_grows_and_clamps() -> None:
    """DOD-03 / REQ-02: backoff grows per streak, clamps and skips quarantines."""

    engine, curriculum, _ = _build(
        [],
        fingerprint_quarantine_threshold=10,
        blocked_backoff_base_sec=1.0,
        blocked_backoff_max_sec=3.0,
    )
    assert engine._blocked_backoff_sec(VALUE_ERROR) == 0.0

    observed: list[float] = []
    for index in range(4):
        name = f"task-{index}"
        curriculum.specs.append(_spec(name))
        engine.errors[name] = ValueError("boom")
        engine.run_cycle()
        observed.append(engine._blocked_backoff_sec(VALUE_ERROR))

    assert observed == sorted(observed)
    assert observed == [1.0, 2.0, 3.0, 3.0]
    assert observed[-1] == engine._config.blocked_backoff_max_sec
    assert engine.fingerprint_state()["quarantined_count"] == 0


def test_quarantine_activates_after_threshold() -> None:
    """DOD-04 / REQ-02: open exactly at N, never at N-1."""

    engine, curriculum, clock = _build(
        [_spec("a"), _spec("b")],
        errors=_failing(["a", "b", "c"], ValueError("boom")),
        fingerprint_quarantine_threshold=3,
        fingerprint_quarantine_sec=60.0,
    )
    engine.run_cycle()
    bucket = engine._buckets[VALUE_ERROR]
    assert bucket.consecutive_blocked == 2
    assert bucket.circuit_state is FingerprintCircuitState.CLOSED
    assert bucket.quarantine_until is None
    assert engine._blocked_backoff_sec(VALUE_ERROR) == pytest.approx(0.2)

    curriculum.specs.append(_spec("c"))
    engine.run_cycle()
    assert bucket.consecutive_blocked == 3
    assert bucket.circuit_state is FingerprintCircuitState.OPEN
    assert bucket.quarantine_until == pytest.approx(clock.now + 60.0)
    assert engine._blocked_backoff_sec(VALUE_ERROR) == 0.0

    payload = engine.fingerprint_state()
    assert payload["quarantined_count"] == 1
    assert payload["fingerprints"][0]["quarantine_active"] is True
    assert payload["fingerprints"][0]["remaining_sec"] == pytest.approx(60.0)
    quarantined = [
        event for event in engine.events if event["event"] == "FINGERPRINT_QUARANTINED"
    ]
    assert quarantined == [
        {"event": "FINGERPRINT_QUARANTINED", "fingerprint": VALUE_ERROR, "cycle": 2}
    ]


def test_quarantined_fingerprint_is_skipped() -> None:
    """DOD-05 / REQ-02: skip the interval, keep other fingerprints running."""

    engine, curriculum, _ = _build(
        [_spec("a"), _spec("b")],
        errors={
            "a": ValueError("boom"),
            "b": ValueError("boom"),
            "c": TimeoutError("slow"),
        },
        fingerprint_quarantine_threshold=2,
    )
    engine.run_cycle()
    assert engine._buckets[VALUE_ERROR].circuit_state is FingerprintCircuitState.OPEN

    # An operator requeues "b" (same ValueError fingerprint) while "c" is new.
    curriculum.specs[1].state = TaskState.PENDING
    curriculum.specs.extend([_spec("c")])
    engine.stepped.clear()
    result = engine.run_cycle()

    assert "b" not in engine.stepped  # quarantined: not enqueued, not diagnosed
    assert engine.stepped == ["c"]
    assert result.steps == 1  # a skip does not consume the step budget
    assert engine._buckets[TIMEOUT_ERROR].consecutive_blocked == 1
    assert engine._buckets[VALUE_ERROR].consecutive_blocked == 2

    skipped = [event for event in engine.events if event["event"] == "FINGERPRINT_SKIPPED"]
    assert skipped == [
        {
            "event": "FINGERPRINT_SKIPPED",
            "task": "b",
            "fingerprint": VALUE_ERROR,
            "cycle": 2,
        }
    ]
    assert engine.snapshot()["monitored"] is None


def test_circuit_closes_after_cooldown() -> None:
    """DOD-06 / REQ-03: cooldown elapses, probe succeeds, circuit closes."""

    engine, _, clock = _build(
        [_spec("a"), _spec("b")],
        errors=_failing(["a", "b"], ValueError("boom")),
        fingerprint_quarantine_threshold=2,
        fingerprint_quarantine_sec=60.0,
    )
    engine.run_cycle()
    bucket = engine._buckets[VALUE_ERROR]
    assert bucket.circuit_state is FingerprintCircuitState.OPEN

    clock.now = bucket.quarantine_until - 0.001
    assert engine.sweep_quarantine() == {
        "now": clock.now,
        "probed": 0,
        "closed": 0,
        "reopened": 0,
    }
    assert bucket.circuit_state is FingerprintCircuitState.OPEN

    clock.now = bucket.quarantine_until
    assert engine.sweep_quarantine()["closed"] == 1
    assert bucket.circuit_state is FingerprintCircuitState.CLOSED
    assert bucket.consecutive_blocked == 0
    assert bucket.quarantine_until is None
    assert engine.fingerprint_state()["quarantined_count"] == 0
    assert engine.events[-1] == {
        "event": "FINGERPRINT_CLOSED",
        "fingerprint": VALUE_ERROR,
        "cycle": 1,
    }


def test_probe_failure_rearms_quarantine() -> None:
    """DOD-07 / REQ-03: a failing or raising probe re-arms the quarantine."""

    for probe in (lambda fingerprint: False, _raising_probe):
        engine, _, clock = _build(
            [_spec("a"), _spec("b")],
            errors=_failing(["a", "b"], ValueError("boom")),
            probe=probe,
            fingerprint_quarantine_threshold=2,
            fingerprint_quarantine_sec=60.0,
        )
        engine.run_cycle()
        bucket = engine._buckets[VALUE_ERROR]
        clock.advance(60.0)
        outcome = engine.sweep_quarantine()
        assert outcome == {"now": clock.now, "probed": 1, "closed": 0, "reopened": 1}
        assert bucket.circuit_state is FingerprintCircuitState.OPEN
        assert bucket.consecutive_blocked == 3
        assert bucket.quarantine_until == pytest.approx(clock.now + 60.0)
        assert bucket.is_quarantined(clock.now) is True
        assert engine.events[-1]["event"] == "FINGERPRINT_REOPENED"


def _raising_probe(fingerprint: str) -> bool:
    raise RuntimeError(f"probe exploded for {fingerprint}")


def test_scheduler_sweeps_without_holding_the_lock() -> None:
    """DOD-08 / REQ-03: daemon sweep, idempotent start, clean stop, no lock hold."""

    entered = threading.Event()
    release = threading.Event()

    def blocking_probe(fingerprint: str) -> bool:
        entered.set()
        release.wait(timeout=5)
        return True

    engine, _, clock = _build(
        [_spec("a"), _spec("b")],
        errors=_failing(["a", "b"], ValueError("boom")),
        probe=blocking_probe,
        fingerprint_quarantine_threshold=2,
        fingerprint_quarantine_sec=1.0,
        quarantine_sweep_interval_sec=0.01,
    )
    engine.run_cycle()
    clock.advance(1.0)

    thread = engine.start_scheduler()
    try:
        assert engine.start_scheduler() is thread  # idempotent
        assert entered.wait(timeout=5) is True
        assert thread.daemon is True

        # The probe blocks outside the lock: snapshot() and run_cycle() proceed.
        started = time.monotonic()
        assert engine.snapshot()["monitored"] is None
        engine.run_cycle()
        assert time.monotonic() - started < 2.0
        assert engine._buckets[VALUE_ERROR].circuit_state is FingerprintCircuitState.HALF_OPEN
    finally:
        release.set()
        engine.stop_scheduler()

    assert thread.is_alive() is False
    assert engine._buckets[VALUE_ERROR].circuit_state is FingerprintCircuitState.CLOSED
    engine.stop_scheduler()  # idempotent stop


def test_bucket_registry_is_bounded() -> None:
    """DOD-12 / REQ-01: cap CLOSED buckets, never evict a quarantined one."""

    clock = FrozenClock(10.0)
    specs = [_spec("a1"), _spec("a2"), _spec("b"), _spec("c"), _spec("d")]
    errors = {
        "a1": ErrorA("x"),
        "a2": ErrorA("x"),
        "b": ErrorB("x"),
        "c": ErrorC("x"),
        "d": ErrorD("x"),
    }
    engine, _, _ = _build(
        specs,
        errors=errors,
        clock=clock,
        fingerprint_quarantine_threshold=2,
        max_fingerprint_buckets=2,
    )
    engine.run_cycle()

    pinned = error_fingerprint(ErrorA("x"))
    assert engine._buckets[pinned].circuit_state is FingerprintCircuitState.OPEN
    assert len(engine._buckets) <= 2
    assert pinned in engine._buckets
    assert all(
        bucket.circuit_state is FingerprintCircuitState.OPEN
        for bucket in engine._buckets.values()
        if bucket.fingerprint == pinned
    )
    # Every surviving CLOSED bucket is one of the most recent fingerprints.
    closed = [
        bucket.fingerprint
        for bucket in engine._buckets.values()
        if bucket.circuit_state is FingerprintCircuitState.CLOSED
    ]
    assert closed in ([], [error_fingerprint(ErrorD("x"))])


def test_successful_cycle_clears_bucket_streak() -> None:
    """REQ-02 CLEAR row: a committed step resets its fingerprint's streak."""

    engine, curriculum, _ = _build(
        [_spec("flaky")],
        errors={"flaky": ValueError("boom")},
        fingerprint_quarantine_threshold=3,
    )
    engine.run_cycle()
    assert engine._buckets[VALUE_ERROR].consecutive_blocked == 1

    engine.errors.clear()
    curriculum.specs[0].state = TaskState.PENDING
    engine.run_cycle()

    bucket = engine._buckets[VALUE_ERROR]
    assert bucket.consecutive_blocked == 0
    assert bucket.circuit_state is FingerprintCircuitState.CLOSED
    assert bucket.quarantine_until is None
    assert "flaky" not in engine._task_fingerprint
