"""Task cancellation and stop-cleanup of the maintainer engine (UTILTOOLS-76).

Covers the task brief REQ-01 .. REQ-05: ``cancel_task()`` signalling and
bounded wait (DOD-01/DOD-02), the ``should_stop()`` checkpoints in
``run_cycle`` / ``run_forever`` with the stop-latency invariant (DOD-03),
runtime-marker lifecycle and memory-store pruning on stop/shutdown (DOD-04/DOD-05),
and the clean ``STOPPED`` transition with the ``STOP_HONOURED`` log line
(DOD-06). The clock and sleep are injected, so the 300 s default bound and the
cancel timeout are asserted at their boundaries without a real sleep.
"""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import (
    CancelReport,
    CycleResult,
    MaintainerEngine,
    RUNTIME_HEARTBEAT_FILE,
    RUNTIME_PID_FILE,
)
from ai_maintainer.memory import MemoryStore
from ai_maintainer.shutdown import (
    CLEANUP_CLEAR_RUNTIME_MARKERS,
    CLEANUP_PRUNE_MEMORY,
    CLEANUP_PRUNE_STALE_TASK_SLOTS,
    EngineState,
    SHUTDOWN_STATE_TMP,
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

    def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)
        self.clock.advance(seconds)

    @property
    def total(self) -> float:
        return sum(self.calls)


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
    memory: MemoryStore | None = None,
) -> MaintainerEngine:
    clock = clock if clock is not None else FrozenClock()
    sleeper = sleeper if sleeper is not None else FakeSleeper(clock)
    cfg = config if config is not None else MaintainerConfig()
    root: Path | None
    if state_root == "default":
        root = tmp_path / "state"
    elif state_root is None:
        root = None
    else:
        root = Path(state_root)
    curriculum = TaskCurriculum(specs=specs, config=cfg, clock=clock)
    return MaintainerEngine(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=sleeper,
        state_root=root,
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
        memory=memory,
    )


def _audit_lines(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    raw = path.read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in raw if line.strip()]


def _events_named(engine: MaintainerEngine, name: str) -> list[dict]:
    return [event for event in engine.events if event["event"] == name]


# --------------------------------------------------------------- REQ-01 cancel


def test_cancel_task_terminates_running_cycle(tmp_path: Path) -> None:
    """DOD-01/DOD-02: cancel signals the in-flight cycle and the task lands in
    CANCELLED; the bounded wait only ends once the cycle has unwound."""

    entered = threading.Event()

    class BlockingProbe(MaintainerEngine):
        def _monitor_step(self, task: TaskSpec) -> None:
            entered.set()
            # Hold the step open in real time until cancel_task() flips the flag.
            # A real-time ``Event.wait`` consumes zero injected-clock time, so the
            # worker thread reliably unwinds ``run_cycle()`` no matter how fast the
            # ``cancel_task()`` poll loop advances the (non-GIL-releasing) fake
            # clock. Under that injection the fake-clock ``timed_out`` flag and
            # ``waited_sec`` reflect real-time scheduling rather than the fake
            # budget, so this test asserts the deterministic terminal-state
            # invariants only; the ``timed_out`` boundary is proven by the
            # single-threaded timeout test below instead.
            self._cancel.wait(5.0)

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=4)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    engine = BlockingProbe(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )
    worker = threading.Thread(target=engine.run_cycle, name="cycle")
    worker.start()
    assert entered.wait(5.0)

    report = engine.cancel_task(timeout_sec=10.0)

    worker.join(5.0)
    assert not worker.is_alive()
    assert isinstance(report, CancelReport)
    assert report.cancelled is True
    assert report.task == "task-a"
    assert report.state == "CANCELLED"
    assert report.waited_sec >= 0.0
    assert curriculum["task-a"].state is TaskState.CANCELLED
    assert _events_named(engine, "TASK_CANCEL_REQUESTED")
    assert [event for event in _events_named(engine, "TASK_CANCELLED")]
    payload = report.to_dict()
    assert payload["task"] == "task-a"
    assert payload["state"] == "CANCELLED"
    assert payload["cancelled"] is True
    assert payload["reason"] == report.reason


def test_cancel_task_times_out_when_step_blocks(tmp_path: Path) -> None:
    """DOD-02: a hung step that ignores the flag stops the wait at the bound."""

    release = threading.Event()
    entered = threading.Event()

    class HungProbe(MaintainerEngine):
        def _monitor_step(self, task: TaskSpec) -> None:
            entered.set()
            release.wait(10.0)  # ignores the cancel flag on purpose

    clock = FrozenClock()
    cfg = MaintainerConfig(max_shutdown_wait_sec=0.5, max_steps_per_cycle=4)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    engine = HungProbe(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )
    worker = threading.Thread(target=engine.run_cycle, name="cycle")
    worker.start()
    assert entered.wait(5.0)

    report = engine.cancel_task(timeout_sec=None)

    assert report.cancelled is True
    assert report.timed_out is True
    assert report.state == "CANCELLED"
    assert report.waited_sec <= 0.5

    release.set()
    worker.join(5.0)
    assert not worker.is_alive()
    # The task is no longer monitored: a second cancel is a no-op.
    second = engine.cancel_task(timeout_sec=0.0)
    assert second.cancelled is False
    assert _events_named(engine, "CANCEL_NOOP")


def test_cancel_task_is_a_noop_with_nothing_in_flight(tmp_path: Path) -> None:
    """DOD-01: no monitored task means a cancelled=False report, not an error."""

    engine = _engine(tmp_path, [_spec("task-a")])
    report = engine.cancel_task(timeout_sec=1.0, reason="idle")

    assert report.cancelled is False
    assert report.task is None
    assert report.state is None
    assert report.timed_out is False
    assert report.waited_sec == 0.0
    assert report.reason == "idle"
    assert _events_named(engine, "CANCEL_NOOP")
    assert engine.should_cancel() is False


# --------------------------------------------------- REQ-02 stop checkpoints


def test_run_cycle_breaks_early_when_stop_requested_mid_cycle(
    tmp_path: Path,
) -> None:
    """DOD-03: ``request_stop()`` inside a step stops the cycle at the next
    checkpoint without starting another step."""

    class StopInsideStep(MaintainerEngine):
        calls = 0

        def _monitor_step(self, task: TaskSpec) -> None:
            StopInsideStep.calls += 1
            self.request_stop()

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=2)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    engine = StopInsideStep(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )

    result = engine.run_cycle()

    assert isinstance(result, CycleResult)
    assert StopInsideStep.calls == 1  # the second step never started
    assert result.steps == 1
    assert result.monitored == "task-a"
    # A stop leaves the task RUNNING so ``shutdown()`` can still persist it.
    assert curriculum["task-a"].state is TaskState.RUNNING
    assert engine.should_stop() is True


def _stop_after_second_cycle(
    tmp_path: Path, cfg: MaintainerConfig, clock: FrozenClock, sleeper: FakeSleeper
) -> MaintainerEngine:
    """Engine that requests a stop between cycle 2 and its sleep."""

    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)

    class StopAfterSecondCycle(MaintainerEngine):
        def run_cycle(self) -> CycleResult:
            result = super().run_cycle()
            if self._cycles >= 2:
                self.request_stop("operator-stop")
            return result

    return StopAfterSecondCycle(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=sleeper,
        state_root=tmp_path / "state",
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )


def test_request_stop_breaks_run_forever_within_interval(tmp_path: Path) -> None:
    """DOD-05: the loop breaks after the stopping cycle instead of paying a
    further full interval, and lands in STOPPED (INV-STOP-LATENCY-1)."""

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=1, max_shutdown_wait_sec=5.0)
    engine = _stop_after_second_cycle(tmp_path, cfg, clock, FakeSleeper(clock))

    started = time.monotonic()
    report = engine.run_forever(interval_sec=1.0)
    elapsed = time.monotonic() - started

    assert engine._cycles == 2
    assert elapsed < 2.0
    assert report.state is EngineState.STOPPED
    assert engine.state is EngineState.STOPPED


def test_run_forever_logs_shutdown_reason(tmp_path: Path) -> None:
    """DOD-05: exactly one STOP_HONOURED carries the operator's reason and the
    audit record shows the wind-down ran."""

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=1, max_shutdown_wait_sec=5.0)
    engine = _stop_after_second_cycle(tmp_path, cfg, clock, FakeSleeper(clock))

    report = engine.run_forever(interval_sec=1.0)

    stop_events = _events_named(engine, "STOP_HONOURED")
    assert len(stop_events) == 1
    assert stop_events[0]["reason"] == "operator-stop"
    assert stop_events[0]["stop_requested"] is True
    record = _audit_lines(tmp_path / "data" / "maintainer_shutdown.log")[-1]
    assert record["runtime_markers_cleared"] is True
    assert CLEANUP_CLEAR_RUNTIME_MARKERS in record["cleanup_steps"]
    assert CLEANUP_CLEAR_RUNTIME_MARKERS in report.cleanup_steps


def test_runtime_markers_removed_after_stop(tmp_path: Path) -> None:
    """DOD-03/DOD-06: the pid/heartbeat markers really are written while the
    loop runs, so the removal assertions below are not vacuous."""

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=1, max_shutdown_wait_sec=5.0)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    markers = tmp_path / "state"
    pid_seen = False
    heartbeat_seen = False

    class MarkerProbe(MaintainerEngine):
        def run_cycle(self) -> CycleResult:
            nonlocal pid_seen, heartbeat_seen
            result = super().run_cycle()
            pid_seen |= self.pid_file is not None and self.pid_file.exists()
            heartbeat_seen |= (
                self.heartbeat_file is not None and self.heartbeat_file.exists()
            )
            # ``run_forever`` clears the stop flag at entry, so the request has
            # to come from inside the loop (one cycle only).
            self.request_stop("operator-stop")
            return result

    engine = MarkerProbe(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=markers,
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )
    report = engine.run_forever(interval_sec=1.0)

    assert pid_seen is True
    assert heartbeat_seen is True
    assert report.state is EngineState.STOPPED
    assert not (markers / RUNTIME_PID_FILE).exists()
    assert not (markers / (RUNTIME_PID_FILE + ".tmp")).exists()
    assert not (markers / RUNTIME_HEARTBEAT_FILE).exists()
    assert not (markers / (RUNTIME_HEARTBEAT_FILE + ".tmp")).exists()


def test_second_run_forever_leaves_no_markers(tmp_path: Path) -> None:
    """SHOULD_FIX (review v1): a second ``run_forever()`` publishes markers at
    entry while ``shutdown()`` returns its cached report without re-running the
    cleanup, so the loop's own exit path must clear them — asserted
    non-vacuously, since the markers are observed to exist mid-cycle."""

    clock = FrozenClock()
    cfg = MaintainerConfig(max_steps_per_cycle=1, max_shutdown_wait_sec=5.0)
    curriculum = TaskCurriculum(specs=[_spec("task-a")], config=cfg, clock=clock)
    markers = tmp_path / "state"

    class SecondLoopProbe(MaintainerEngine):
        loops = 0
        second_loop_markers = False

        def run_cycle(self) -> CycleResult:
            result = super().run_cycle()
            self.loops += 1
            if self.loops == 2:
                self.second_loop_markers = bool(
                    self.pid_file is not None
                    and self.pid_file.exists()
                    and self.heartbeat_file is not None
                    and self.heartbeat_file.exists()
                )
            # ``run_forever`` clears the stop flag at entry, so each loop needs
            # its own request.
            self.request_stop("loop-done")
            return result

    engine = SecondLoopProbe(
        curriculum,
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=markers,
        shutdown_audit_path=tmp_path / "data" / "maintainer_shutdown.log",
    )

    first = engine.run_forever(interval_sec=1.0)
    assert first.state is EngineState.STOPPED
    assert not (markers / RUNTIME_PID_FILE).exists()

    second = engine.run_forever(interval_sec=1.0)

    assert engine.loops == 2
    assert engine.second_loop_markers is True  # markers really were republished
    assert second is first  # ``shutdown()`` served the cached report ...
    assert second.state is EngineState.STOPPED
    # ... so the loop's exit path had to do the clearing itself.
    assert not (markers / RUNTIME_PID_FILE).exists()
    assert not (markers / (RUNTIME_PID_FILE + ".tmp")).exists()
    assert not (markers / RUNTIME_HEARTBEAT_FILE).exists()
    assert not (markers / (RUNTIME_HEARTBEAT_FILE + ".tmp")).exists()


def test_runtime_markers_removed_on_natural_completion(tmp_path: Path) -> None:
    """DOD-06: a wind-down with no stop request (``shutdown()`` after the last
    cycle) also removes markers that were present — non-vacuously, since the
    test writes them first and asserts they exist before the wind-down."""

    markers = tmp_path / "state"
    engine = _engine(tmp_path, [_spec("task-a")], state_root=markers)
    engine._write_runtime_markers()

    pid_path = markers / RUNTIME_PID_FILE
    heartbeat_path = markers / RUNTIME_HEARTBEAT_FILE
    assert pid_path.exists()
    assert heartbeat_path.exists()

    report = engine.shutdown()

    assert report.state is EngineState.STOPPED
    assert report.timed_out is False
    assert not pid_path.exists()
    assert not heartbeat_path.exists()


def test_cancel_task_then_stop_reaches_stopped_cleanly(tmp_path: Path) -> None:
    """Brief REQ-05: cancel, then stop, and the engine reaches STOPPED with the
    wind-down bookkeeping intact."""

    engine = _engine(tmp_path, [_spec("task-a")], state_root=tmp_path / "state")
    cancel = engine.cancel_task(timeout_sec=0.0)
    assert cancel.cancelled is False  # nothing was in flight

    engine.request_stop()
    report = engine.shutdown()

    assert report.state is EngineState.STOPPED
    assert engine.state is EngineState.STOPPED
    assert not (tmp_path / "state" / RUNTIME_PID_FILE).exists()
    assert not (tmp_path / "state" / RUNTIME_HEARTBEAT_FILE).exists()
    assert _events_named(engine, "SHUTDOWN_COMPLETE")


# ------------------------------------------------------- REQ-03 cleanup/prune


def test_memory_pruned_after_cycle_and_on_shutdown(tmp_path: Path) -> None:
    """DOD-07 (INV-PRUNE-1/2/3/4): both the per-cycle trim and the wind-down
    trim are visible in ``maintainer_memory.json`` itself, not just in the
    in-process store, and the audit record names the drops."""

    path = tmp_path / "maintainer_memory.json"
    store = MemoryStore(path)
    for index in range(5):
        store.add_run({"run_id": f"r{index}", "task_id": "task-a", "state": "COMPLETED"})
    for index in range(3):
        store.add_bug_fix({"fix_id": f"f{index}", "task_id": "task-a", "summary": "s"})
    for index in range(4):
        store.add_learning({"learning_id": f"l{index}", "text": f"note {index}"})
    store.save(known_task_ids={"task-a"})

    cfg = MaintainerConfig(
        memory_max_runs=2,
        memory_max_bug_fixes=1,
        memory_max_learnings=2,
        max_steps_per_cycle=1,
        max_shutdown_wait_sec=0.5,
    )
    engine = _engine(
        tmp_path,
        [_spec("task-a")],
        config=cfg,
        memory=store,
        state_root=tmp_path / "state",
    )

    # --- per-cycle trim reaches the document ------------------------------
    engine.run_cycle()
    after_cycle = json.loads(path.read_text(encoding="utf-8"))
    assert len(after_cycle["runs"]) == 2
    assert len(after_cycle["bug_fixes"]) == 1
    assert len(after_cycle["learnings"]) == 2

    # --- grow the document again, then trim it in the wind-down -----------
    for index in range(3):
        store.add_run(
            {"run_id": f"r{10 + index}", "task_id": "task-a", "state": "COMPLETED"}
        )
    for index in range(2):
        store.add_bug_fix({"fix_id": f"f{10 + index}", "task_id": "task-a", "summary": "s"})
    for index in range(2):
        store.add_learning({"learning_id": f"l{10 + index}", "text": f"note {index}"})
    store.save(known_task_ids={"task-a"})
    grown = json.loads(path.read_text(encoding="utf-8"))
    assert len(grown["runs"]) == 5  # precondition: the document is over the cap

    engine.request_stop()
    report = engine.shutdown()

    assert CLEANUP_PRUNE_MEMORY in report.cleanup_steps
    record = _audit_lines(tmp_path / "data" / "maintainer_shutdown.log")[-1]
    assert record["memory_dropped"] == {"runs": 3, "bug_fixes": 2, "learnings": 2}

    # The persisted document — not merely the in-memory store — is trimmed.
    persisted = json.loads(path.read_text(encoding="utf-8"))
    assert len(persisted["runs"]) == 2
    assert len(persisted["bug_fixes"]) == 1
    assert len(persisted["learnings"]) == 2
    # ``prune()`` keeps the newest entries, so the surviving pair is r11/r12.
    assert [item["run_id"] for item in persisted["runs"]] == ["r11", "r12"]
    assert len(store.records()["runs"]) == 2


def test_run_cycle_persists_pruned_memory_document(tmp_path: Path) -> None:
    """DOD-04 (INV-PRUNE-2): a cycle trims the store before it persists, so
    the document on disk never grows past the configured maxima."""

    path = tmp_path / "maintainer_memory.json"
    store = MemoryStore(path)
    for index in range(7):
        store.add_run({"run_id": f"r{index}", "task_id": "task-a", "state": "COMPLETED"})
    store.save(known_task_ids={"task-a"})

    cfg = MaintainerConfig(memory_max_runs=3, max_steps_per_cycle=1)
    engine = _engine(
        tmp_path,
        [_spec("task-a")],
        config=cfg,
        memory=store,
        state_root=tmp_path / "state",
    )

    engine.run_cycle()

    reloaded = json.loads(path.read_text(encoding="utf-8"))
    assert len(reloaded["runs"]) == 3
    # Oldest-first trim keeps the most recent three records.
    assert [record["run_id"] for record in reloaded["runs"]] == ["r4", "r5", "r6"]


def test_stale_task_slot_tmp_pruned_on_shutdown(tmp_path: Path) -> None:
    """DOD-04: stale slots are dropped, committed state and live slots survive."""

    root = tmp_path / "state"
    stale = root / "gone-task"
    stale.mkdir(parents=True)
    (stale / SHUTDOWN_STATE_TMP).write_text("{}", encoding="utf-8")
    live = root / "task-a"
    live.mkdir(parents=True)
    (live / SHUTDOWN_STATE_TMP).write_text("{}", encoding="utf-8")
    committed = root / "task-b"
    committed.mkdir(parents=True)
    (committed / SHUTDOWN_STATE_TMP).write_text("{}", encoding="utf-8")
    (committed / "_shutdown_state.json").write_text("{}", encoding="utf-8")

    engine = _engine(
        tmp_path,
        [_spec("task-a"), _spec("task-b", state=TaskState.COMPLETED)],
        state_root=root,
    )
    engine.request_stop()
    report = engine.shutdown()

    assert CLEANUP_PRUNE_STALE_TASK_SLOTS in report.cleanup_steps
    assert not stale.exists()
    assert live.exists()  # tmp dropped, but the live slot directory survives
    assert not (live / SHUTDOWN_STATE_TMP).exists()
    assert (committed / "_shutdown_state.json").exists()  # committed state survives
    pruned = _events_named(engine, "STALE_TASK_SLOT_PRUNED")
    assert [event["task"] for event in pruned] == ["gone-task"]
    record = _audit_lines(tmp_path / "data" / "maintainer_shutdown.log")[-1]
    assert record["pruned_task_slots"] == ["gone-task"]


def test_shutdown_without_state_root_skips_markers_and_persist(
    tmp_path: Path,
) -> None:
    """DOD-05 guard: with no state root the engine still reaches STOPPED and
    skips the root-bound cleanup steps without failing."""

    engine = _engine(tmp_path, [_spec("task-a")], state_root=None)
    assert engine.pid_file is None
    assert engine.heartbeat_file is None

    engine.request_stop()
    report = engine.shutdown()

    assert report.state is EngineState.STOPPED
    assert CLEANUP_CLEAR_RUNTIME_MARKERS in report.cleanup_steps
    assert CLEANUP_PRUNE_STALE_TASK_SLOTS in report.cleanup_steps
    assert not report.failed_steps
    assert report.persist_skipped_reason is not None
