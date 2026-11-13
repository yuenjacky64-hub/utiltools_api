"""Per-cycle diagnosis budget and diagnosis history for the maintainer engine.

Covers brief REQ-01 .. REQ-05 (plan DOD-01 .. DOD-05). The clock is injected so
the recorded ``timestamp`` values are asserted exactly, and the monitor step is
scripted through the existing ``_monitor_step`` seam, so no production seam had
to change for these tests.
"""

from __future__ import annotations

import pytest

from ai_maintainer import config as config_module
from ai_maintainer.config import DEFAULT_MAX_DIAGNOSIS_BUDGET, MaintainerConfig
from ai_maintainer.engine import (
    DIAGNOSIS_BUDGET_EXHAUSTED_EVENT,
    MaintainerEngine,
    error_fingerprint,
)
from ai_maintainer.tasks.curriculum import Difficulty, TaskCurriculum, TaskSpec, TaskState

VALUE_ERROR = "builtins.ValueError"


class ErrorA(Exception):
    """Failing step error whose fingerprint is asserted in the history."""


class FrozenClock:
    """Clock that only moves when the test moves it."""

    def __init__(self, now: float = 100.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now


def _spec(name: str, *, steps_required: int = 4) -> TaskSpec:
    """Spec with recency disabled (``last_proposed_cycle`` far ahead)."""

    return TaskSpec(
        name=name,
        difficulty=Difficulty.EASY,
        steps_required=steps_required,
        last_proposed_cycle=100,
        state=TaskState.PENDING,
    )


class ScriptedEngine(MaintainerEngine):
    """Engine whose monitor step raises the scripted error of a task, if any."""

    def __init__(self, *args, errors=None, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.errors: dict[str, BaseException] = dict(errors or {})

    def _monitor_step(self, task: TaskSpec) -> None:
        error = self.errors.get(task.name)
        if error is not None:
            raise error
        return None


def _build(
    specs: list[TaskSpec],
    *,
    errors: dict[str, BaseException] | None = None,
    **config_kwargs,
) -> tuple[ScriptedEngine, FrozenClock]:
    clock = FrozenClock()
    config_kwargs.setdefault("max_steps_per_cycle", 8)
    config = MaintainerConfig(**config_kwargs)
    curriculum = TaskCurriculum(specs=specs, config=config, clock=clock)
    engine = ScriptedEngine(curriculum, config=config, clock=clock, errors=errors)
    return engine, clock


def _failing(steps_required: int = 4) -> list[TaskSpec]:
    return [_spec(name, steps_required=steps_required) for name in ("a", "b", "c")]


def _errors(names: tuple[str, ...]) -> dict[str, BaseException]:
    return {name: ErrorA(name) for name in names}


def test_config_default_and_validation(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """DOD-01 / REQ-01: default 3, all config layers, and ``>= 1`` validation."""

    assert DEFAULT_MAX_DIAGNOSIS_BUDGET == 3
    assert MaintainerConfig().max_diagnosis_budget == 3

    monkeypatch.setattr(config_module, "_load_must_config", lambda: {})
    bare = tmp_path / "bare"
    bare.mkdir()
    with_json = tmp_path / "with_json"
    with_json.mkdir()
    (with_json / "config.json").write_text(
        '{"max_diagnosis_budget": 4}', encoding="utf-8"
    )
    env = {"MAINTAINER_MAX_DIAGNOSIS_BUDGET": "5"}

    # CLI override > env > config.json > must_config.py > built-in default.
    assert (
        MaintainerConfig.load(
            maintainer_root=with_json, env=env, max_diagnosis_budget=6
        ).max_diagnosis_budget
        == 6
    )
    assert MaintainerConfig.load(maintainer_root=with_json, env=env).max_diagnosis_budget == 5
    assert MaintainerConfig.load(maintainer_root=with_json, env={}).max_diagnosis_budget == 4
    assert MaintainerConfig.load(maintainer_root=bare, env={}).max_diagnosis_budget == 3

    with pytest.raises(ValueError, match="max_diagnosis_budget must be >= 1"):
        MaintainerConfig(max_diagnosis_budget=0)
    with pytest.raises(ValueError, match="max_diagnosis_budget must be >= 1"):
        MaintainerConfig(max_diagnosis_budget=-1)
    with pytest.raises(ValueError, match="max_diagnosis_budget must be an integer"):
        MaintainerConfig.load(
            maintainer_root=bare, env={"MAINTAINER_MAX_DIAGNOSIS_BUDGET": "three"}
        )


def test_budget_enforced() -> None:
    """DOD-02 / REQ-02: the first N windows open, then the gate refuses."""

    engine, _ = _build(_failing(), max_diagnosis_budget=2)

    # The gate itself: N grants, then a hard ``False``.
    assert engine._should_diagnose(1) is True
    assert engine._should_diagnose(1) is True
    assert engine._should_diagnose(1) is False
    assert engine._diagnosis_attempts == {1: 2}

    engine2, _ = _build(_failing(), errors=_errors(("a", "b", "c")), max_diagnosis_budget=2)
    result2 = engine2.run_cycle()

    # All three tasks failed, but only two diagnosis windows were granted, and
    # the refusal is audited with the number of attempts actually made.
    assert len([e for e in engine2.events if e["event"] == "FAILED"]) == 3
    attempts2 = [e for e in result2.issues_detected if e.get("kind") == "ATTEMPT"]
    assert len(attempts2) == 2
    exhausted2 = [
        e for e in engine2.events if e["event"] == DIAGNOSIS_BUDGET_EXHAUSTED_EVENT
    ]
    assert exhausted2 and exhausted2[0]["attempts_made"] == 2
    assert exhausted2[0]["budget"] == 2


def test_history_recorded() -> None:
    """DOD-03 / REQ-03: attempt metadata lands in ``issues_detected``."""

    engine, clock = _build(_failing(), errors=_errors(("a", "b", "c")), max_diagnosis_budget=3)
    result = engine.run_cycle()

    attempts = [entry for entry in result.issues_detected if entry.get("kind") == "ATTEMPT"]
    assert len(attempts) == 3

    first = attempts[0]
    assert first["cycle"] == 1
    assert first["task"] == "a"
    assert first["timestamp"] == clock.now
    assert first["error_type"] == error_fingerprint(ErrorA("a"))
    assert first["error_type"].endswith(".ErrorA")
    assert first["outcome"] == "FAILED"
    assert first["budget_state"] == {
        "limit": 3,
        "used": 1,
        "remaining": 2,
        "exhausted": False,
    }
    assert attempts[-1]["budget_state"]["used"] == 3
    assert attempts[-1]["budget_state"]["exhausted"] is True
    assert attempts[-1]["error_type"].endswith(".ErrorA")


def test_exhaustion_audit_event() -> None:
    """DOD-04 / REQ-04: exhaustion is audited with limit, attempts and outcome."""

    engine, clock = _build(_failing(), errors=_errors(("a", "b", "c")), max_diagnosis_budget=1)
    result = engine.run_cycle()

    exhausted = [
        event
        for event in engine.events
        if event["event"] == DIAGNOSIS_BUDGET_EXHAUSTED_EVENT
    ]
    assert exhausted
    assert exhausted[0] == {
        "event": DIAGNOSIS_BUDGET_EXHAUSTED_EVENT,
        "cycle": 1,
        "budget": 1,
        "attempts_made": 1,
        "outcome": "EXHAUSTED",
    }

    # REQ-04 "in the cycle report": the same refusal is part of the report the
    # cycle returns, so ``CycleResult.to_dict()`` carries it too.
    reported = [e for e in result.issues_detected if e.get("kind") == "EXHAUSTED"]
    assert len(reported) == 1
    assert reported[0]["cycle"] == 1
    assert reported[0]["budget_state"] == {
        "limit": 1,
        "used": 1,
        "remaining": 0,
        "exhausted": True,
    }
    assert reported[0]["attempts_made"] == 1
    assert reported[0]["outcome"] == "EXHAUSTED"
    assert reported[0]["error_type"].endswith(".ErrorA")
    assert reported[0]["timestamp"] == clock.now
    assert "EXHAUSTED" in [e["kind"] for e in result.to_dict()["issues_detected"]]

    # Safe rollback: three tasks failed, yet the refusal after the first
    # window means no further diagnosis was attempted for this cycle.
    assert len([e for e in engine.events if e["event"] == "FAILED"]) == 3
    assert len([e for e in result.issues_detected if e.get("kind") == "ATTEMPT"]) == 1

    # Only the first refusal of the cycle is audited, so a refused-adoption
    # streak cannot evict earlier audit rows from the bounded event log.
    assert len(exhausted) == 1


def test_reset_on_success() -> None:
    """DOD-05 / REQ-05: a SUCCESS cycle restores the full budget and says so."""

    specs = [_spec("a", steps_required=4), _spec("b", steps_required=4)]
    engine, clock = _build(specs, max_diagnosis_budget=2, max_steps_per_cycle=4)

    first = engine.run_cycle()
    assert first.outcome == "SUCCESS"
    # Only cycle 1's slice is dropped: nothing is left to leak into cycle 2.
    assert engine._diagnosis_attempts == {}
    assert engine._diagnosis_history == {}
    reset_entries = [
        entry for entry in first.issues_detected if entry.get("kind") == "BUDGET_RESET"
    ]
    assert len(reset_entries) == 1
    assert reset_entries[0]["cycle"] == 1
    assert reset_entries[0]["outcome"] == "SUCCESS"
    assert reset_entries[0]["attempts_before_reset"] == 1
    assert reset_entries[0]["timestamp"] == clock.now
    assert reset_entries[0]["budget_state"] == {
        "limit": 2,
        "used": 0,
        "remaining": 2,
        "exhausted": False,
    }

    # The next cycle starts from a full budget again.
    second = engine.run_cycle()
    assert second.outcome == "SUCCESS"
    second_reset = [
        entry for entry in second.issues_detected if entry.get("kind") == "BUDGET_RESET"
    ]
    assert second_reset and second_reset[0]["attempts_before_reset"] == 1
    assert engine._diagnosis_attempts == {}


def test_non_success_cycle_does_not_leak() -> None:
    """REQ-01/REQ-03: a non-SUCCESS cycle neither leaks nor pre-spends the budget.

    Regression for the reviewer probe ``report_cycles [1, 2]``: cycle 1 used up
    its budget without a SUCCESS reset, and its entries then showed up in
    cycle 2's report while cycle 2's own budget looked partly spent.
    """

    engine, _ = _build(_failing(), max_diagnosis_budget=2)

    for _ in range(3):
        if engine._should_diagnose(1):
            engine._record_diagnosis_attempt(cycle=1, task=_spec("a"))
            engine._record_diagnosis_outcome("FAILED", cycle=1, error=ErrorA("a"))
    first = engine._diagnosis_report(1)  # cycle 1 ends non-SUCCESS: no reset
    assert [entry["kind"] for entry in first] == ["ATTEMPT", "ATTEMPT", "EXHAUSTED"]

    # Cycle 2 starts from its own, fully available budget.
    assert engine._should_diagnose(2) is True
    engine._record_diagnosis_attempt(cycle=2, task=_spec("b"))
    engine._record_diagnosis_outcome("FAILED", cycle=2, error=ErrorA("b"))
    second = engine._diagnosis_report(2)
    assert {entry["cycle"] for entry in second} == {2}
    assert [entry["kind"] for entry in second] == ["ATTEMPT"]
    assert second[0]["budget_state"] == {
        "limit": 2,
        "used": 1,
        "remaining": 1,
        "exhausted": False,
    }
    # Nothing accumulates between cycles.
    assert engine._diagnosis_history == {}
    assert engine._diagnosis_pending == {}
    assert engine._diagnosis_attempts == {}
    assert engine._diagnosis_exhausted == {}


def test_history_bounded_by_budget() -> None:
    """REQ-01/REQ-03: a failing streak cannot grow the history without bound."""

    engine, _ = _build(_failing(), max_diagnosis_budget=2)

    for _ in range(50):
        engine._should_diagnose(7)
        engine._record_diagnosis_attempt(cycle=7, task=_spec("a"))
        engine._record_diagnosis_outcome("FAILED", cycle=7, error=ErrorA("a"))

    # The gate allows exactly ``limit`` windows, and the deque is capped at the
    # same size, so repeated attempts for one cycle stay bounded.
    assert len(engine._diagnosis_history[7]) == 2
    assert engine._diagnosis_history[7].maxlen == 2
    assert engine._diagnosis_attempts == {7: 2}


def test_open_window_closed_on_interrupt() -> None:
    """REQ-03: a window still open when the cycle ends is stamped, not lost."""

    engine, _ = _build(_failing(), max_diagnosis_budget=3)
    assert engine._should_diagnose(4) is True
    engine._record_diagnosis_attempt(cycle=4, task=_spec("a"))

    issues = engine._diagnosis_report(4)

    assert [entry["kind"] for entry in issues] == ["ATTEMPT"]
    assert issues[0]["outcome"] == "INTERRUPTED"
    assert issues[0]["error_type"] is None
    # The slot is released, so nothing keeps the dead window alive.
    assert 4 not in engine._diagnosis_pending
    assert 4 not in engine._diagnosis_history


def test_success_reset_leaves_other_cycles_alone() -> None:
    """REQ-05: one cycle's SUCCESS reset must not delete a peer's report data."""

    engine, _ = _build(_failing(), max_diagnosis_budget=3)
    assert engine._should_diagnose(11) is True
    engine._record_diagnosis_attempt(cycle=11, task=_spec("a"))

    summary = engine._reset_diagnosis_budget(cycle=12)

    assert summary["kind"] == "BUDGET_RESET"
    assert summary["attempts_before_reset"] == 0
    assert len(engine._diagnosis_history[11]) == 1
    assert 11 in engine._diagnosis_pending
    assert engine._diagnosis_attempts == {11: 1}
