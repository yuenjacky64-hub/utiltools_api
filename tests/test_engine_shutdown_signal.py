"""Signal-driven graceful shutdown of the maintainer engine (UTILTOOLS-80).

Covers brief REQ-01 .. REQ-03 and plan DOD-01 .. DOD-08. Assertions read
persisted artifacts back off disk (heartbeat document, memory JSON, audit log)
or recorded events, so none can pass on an in-memory echo. The clock and the
sleep seam are injected; the in-flight test uses real threads because "the cycle
finishes its step first" is exactly the concurrency property under test.
"""

from __future__ import annotations

import json
import signal
import threading
import time
from datetime import datetime
from pathlib import Path

import pytest

import ai_maintainer.engine as engine_module
from ai_maintainer.config import MaintainerConfig
from ai_maintainer.consistency import validate_memory_document
from ai_maintainer.engine import CycleResult, MaintainerEngine
from ai_maintainer.memory.store import MemoryStore
from ai_maintainer.shutdown import CLEANUP_CLEAR_RUNTIME_MARKERS, EngineState
from ai_maintainer.tasks.curriculum import Difficulty, TaskCurriculum, TaskSpec, TaskState


class FrozenClock:
    """Clock that only moves when a test (or the fake sleeper) moves it."""

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
    cls: type[MaintainerEngine] = MaintainerEngine,
    config: MaintainerConfig | None = None,
    frozen: bool = True,
) -> MaintainerEngine:
    cfg = config if config is not None else MaintainerConfig()
    audit = tmp_path / "data" / "maintainer_shutdown.log"
    audit.parent.mkdir(parents=True, exist_ok=True)
    if not frozen:
        return cls(
            TaskCurriculum(specs=specs, config=cfg),
            config=cfg,
            state_root=tmp_path / "state",
            shutdown_audit_path=audit,
        )
    clock = FrozenClock()
    return cls(
        TaskCurriculum(specs=specs, config=cfg, clock=clock),
        config=cfg,
        clock=clock,
        sleep=FakeSleeper(clock),
        state_root=tmp_path / "state",
        shutdown_audit_path=audit,
    )


def _memory(tmp_path: Path) -> tuple[MemoryStore, Path]:
    path = tmp_path / "data" / "maintainer_memory.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    return MemoryStore(path), path


def _events(engine: MaintainerEngine, name: str) -> list[dict]:
    return [event for event in engine.events if event.get("event") == name]


def _audit_lines(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


class Recorder:
    """Wraps ``_save`` so a test can prove it was called exactly once."""

    def __init__(self, store: MemoryStore) -> None:
        self.store = store
        self.calls = 0

    def __call__(self, *, known_task_ids: object = None) -> list[str]:
        self.calls += 1
        return MemoryStore._save(self.store, known_task_ids=known_task_ids)


# ------------------------------------------------ REQ-01 / DOD-01: memory + heartbeat


def test_graceful_shutdown_persists_memory_and_stamps_heartbeat(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """DOD-01: ``_save()`` runs before teardown, then the terminal heartbeat."""

    store, memory_path = _memory(tmp_path)
    engine = _engine(tmp_path, [_spec("task-a")])
    engine.install_memory(store)
    recorder = Recorder(store)
    monkeypatch.setattr(store, "_save", recorder)
    engine._write_runtime_markers()
    heartbeat = engine.heartbeat_file
    assert heartbeat is not None
    writes: list[tuple[Path, str]] = []
    real_write = engine_module.atomic_write_text

    def record(path: Path, text: str) -> None:
        writes.append((Path(path), text))
        real_write(path, text)

    monkeypatch.setattr(engine_module, "atomic_write_text", record)

    report = engine.graceful_shutdown()

    assert recorder.calls >= 1
    assert memory_path.is_file()
    payloads = [json.loads(text) for path, text in writes if Path(path) == heartbeat]
    assert len(payloads) == 1  # one atomic heartbeat write, no in-place mutation
    payload = payloads[0]
    assert payload["state"] == EngineState.STOPPED.value
    assert isinstance(payload["pid"], int)
    assert isinstance(payload["cycle"], int)
    assert isinstance(payload["at"], float)
    assert datetime.fromisoformat(payload["shutdown_at"]).year >= 2024
    assert report.state is EngineState.STOPPED
    event = _events(engine, "GRACEFUL_SHUTDOWN")[-1]
    assert event["memory_persisted"] is True
    assert event["memory_issue_count"] == 0


# ------------------------------------------------------- REQ-01 / DOD-02: in flight


def test_in_flight_cycle_completes_before_teardown(tmp_path: Path) -> None:
    """DOD-02: the step in flight reaches its break point before teardown."""

    started = threading.Event()
    release = threading.Event()

    class Gated(MaintainerEngine):
        steps: list[str] = []

        def _monitor_step(self, task: TaskSpec) -> None:
            type(self).steps.append(task.name)
            started.set()
            release.wait(10.0)

        def _clear_runtime_markers(self) -> bool:
            self.active_cycles_at_teardown = self.active_cycles
            self.cycle_done_at_teardown = self._cycle_done.is_set()
            return super()._clear_runtime_markers()

    cfg = MaintainerConfig(max_steps_per_cycle=3, max_shutdown_wait_sec=10.0)
    engine = _engine(tmp_path, [_spec("task-a")], cls=Gated, config=cfg, frozen=False)
    results: dict[str, object] = {}

    cycle_thread = threading.Thread(
        target=lambda: results.setdefault("cycle", engine.run_cycle()), daemon=True
    )
    cycle_thread.start()
    assert started.wait(10.0)

    drain_thread = threading.Thread(
        target=lambda: results.setdefault("report", engine.graceful_shutdown()),
        daemon=True,
    )
    drain_thread.start()
    deadline = time.monotonic() + 10.0
    while not engine.should_stop() and time.monotonic() < deadline:
        time.sleep(0.005)
    assert engine.should_stop() is True  # stop published while the step runs
    time.sleep(0.02)
    heartbeat = engine.heartbeat_file
    assert heartbeat is not None and heartbeat.exists() is False  # not yet torn down
    release.set()
    cycle_thread.join(10.0)
    drain_thread.join(10.0)

    cycle = results["cycle"]
    report = results["report"]
    assert isinstance(cycle, CycleResult)
    assert cycle.steps == 1  # the step in flight was allowed to finish
    assert cycle.outcome == "SUCCESS"  # REQ-02: not truncated to INTERRUPTED
    assert Gated.steps == ["task-a"]
    assert engine.active_cycles_at_teardown == 0
    assert engine.cycle_done_at_teardown is True
    assert getattr(report, "state") is EngineState.STOPPED


# ------------------------------------- REQ-02 / DOD-03..DOD-05: signal + outcome


@pytest.mark.parametrize(
    ("signum", "reason"),
    [
        (signal.SIGINT, "SIGNAL_SIGINT"),
        (signal.SIGTERM, "SIGNAL_SIGTERM"),
    ],
)
def test_signal_after_review_yields_success_outcome(
    tmp_path: Path, signum: int, reason: str
) -> None:
    """DOD-03/DOD-04: SIGINT/SIGTERM route through ``_handle_signal``."""

    class Signalling(MaintainerEngine):
        def _monitor_step(self, task: TaskSpec) -> None:
            self._handle_signal(signum, None)

    cfg = MaintainerConfig(max_steps_per_cycle=3)
    engine = _engine(tmp_path, [_spec("task-a")], cls=Signalling, config=cfg)

    result = engine.run_cycle()

    assert result.steps == 1
    assert result.outcome == "SUCCESS"
    assert result.to_dict()["outcome"] == "SUCCESS"
    assert engine.should_stop() is True
    engine._publish_signal_reason()
    published = _events(engine, "STOP_REQUESTED")
    assert len(published) == 1
    assert published[0]["reason"] == reason
    assert engine.stop_reason == reason


def test_stop_before_first_step_yields_interrupted(tmp_path: Path) -> None:
    """DOD-05: the one INTERRUPTED case is a stop seen before any step ran."""

    engine = _engine(tmp_path, [_spec("task-a")])
    engine.request_stop()

    result = engine.run_cycle()

    assert result.steps == 0
    assert result.outcome == "INTERRUPTED"


def test_stop_while_queued_at_the_gate_yields_interrupted(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """DOD-05: a stop published while queued admits nothing — INTERRUPTED."""

    engine = _engine(tmp_path, [_spec("task-a")])

    def stop_while_queued(*args: object, **kwargs: object) -> None:
        engine.request_stop()
        return None

    monkeypatch.setattr(engine._gate, "acquire", stop_while_queued)

    result = engine.run_cycle()

    assert result.admitted is False
    assert result.outcome == "INTERRUPTED"
    assert len(_events(engine, "CYCLE_DEFERRED")) == 1


def test_run_forever_installs_and_restores_signal_handlers(tmp_path: Path) -> None:
    """DOD-03: SIGINT/SIGTERM point at ``_handle_signal`` for the run only."""

    seen: dict[str, object] = {}

    class Probe(MaintainerEngine):
        def run_cycle(self) -> CycleResult:
            seen["int"] = signal.getsignal(signal.SIGINT)
            seen["term"] = signal.getsignal(signal.SIGTERM)
            self.request_stop()
            return super().run_cycle()

    before_int = signal.getsignal(signal.SIGINT)
    before_term = signal.getsignal(signal.SIGTERM)
    engine = _engine(tmp_path, [_spec("task-a")], cls=Probe, frozen=False)

    engine.run_forever(interval_sec=0)

    assert seen["int"] == engine._handle_signal
    assert seen["term"] == engine._handle_signal
    assert signal.getsignal(signal.SIGINT) is before_int
    assert signal.getsignal(signal.SIGTERM) is before_term


# ------------------------------------ REQ-03 / DOD-06..DOD-08: persistence, markers


def test_memory_document_reload_matches_records(tmp_path: Path) -> None:
    """DOD-06: the reloaded document equals the records, ``runs`` in order."""

    store, memory_path = _memory(tmp_path)
    store.add_run(
        {
            "run_id": "run-1",
            "task_id": "task-a",
            "state": "SUCCESS",
            "timestamp": "2026-01-01T00:00:00+00:00",
            "outcome": "SUCCESS",
        },
        cycle=1,
    )
    engine = _engine(tmp_path, [_spec("task-a")])
    engine.install_memory(store)

    engine.graceful_shutdown()

    document = json.loads(memory_path.read_text(encoding="utf-8"))
    assert validate_memory_document(document) == []
    assert document["runs"] == store.records()["runs"]
    assert set(document) == set(store.records())
    stamps = [str(run.get("timestamp")) for run in document["runs"]]
    assert stamps == sorted(stamps)
    for key in ("bug_fixes", "learnings"):
        assert isinstance(document[key], list)
    reloaded = MemoryStore.load(memory_path)
    assert reloaded.records() == store.records()


def test_markers_removed_and_no_tmp_siblings(tmp_path: Path) -> None:
    """DOD-07: PID and heartbeat documents and their ``.tmp`` siblings go."""

    engine = _engine(tmp_path, [_spec("task-a")])
    engine._write_runtime_markers()
    pid_file = engine.pid_file
    heartbeat_file = engine.heartbeat_file
    assert pid_file is not None and pid_file.is_file()
    assert heartbeat_file is not None and heartbeat_file.is_file()

    report = engine.graceful_shutdown()

    for marker in (pid_file, heartbeat_file):
        assert marker.exists() is False
        assert marker.with_name(marker.name + ".tmp").exists() is False
    assert CLEANUP_CLEAR_RUNTIME_MARKERS in report.cleanup_steps


def test_second_graceful_shutdown_is_idempotent(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """DOD-08: a second call is a memoized no-op with a single audit line."""

    store, _ = _memory(tmp_path)
    engine = _engine(tmp_path, [_spec("task-a")])
    engine.install_memory(store)
    recorder = Recorder(store)
    monkeypatch.setattr(store, "_save", recorder)

    first = engine.graceful_shutdown()
    calls_after_first = recorder.calls
    second = engine.graceful_shutdown()

    assert second is first
    assert recorder.calls == calls_after_first
    assert len(_audit_lines(tmp_path / "data" / "maintainer_shutdown.log")) == 1
    assert len(_events(engine, "GRACEFUL_SHUTDOWN")) == 1


def test_restart_after_shutdown_is_duplicate_free(tmp_path: Path) -> None:
    """DOD-08: a second engine on the same root adds no duplicate records."""

    store, memory_path = _memory(tmp_path)
    first = _engine(tmp_path, [_spec("task-a")])
    first.install_memory(store)
    assert first.graceful_shutdown().state is EngineState.STOPPED

    restarted = _engine(tmp_path, [_spec("task-a")])
    restarted.install_memory(MemoryStore.load(memory_path))
    result = restarted.run_cycle()
    assert result.outcome == "SUCCESS"
    assert restarted.graceful_shutdown().state is EngineState.STOPPED

    document = json.loads(memory_path.read_text(encoding="utf-8"))
    assert validate_memory_document(document) == []
    ids = [json.dumps(run, sort_keys=True) for run in document["runs"]]
    assert len(ids) == len(set(ids))
    assert (tmp_path / "state").is_dir()
    assert not (tmp_path / "state" / "maintainer.pid").exists()
    assert not (tmp_path / "state" / "maintainer.heartbeat").exists()


def test_exit_process_is_opt_in(tmp_path: Path) -> None:
    """DOD-08: library code only raises ``SystemExit`` when asked to."""

    engine = _engine(tmp_path, [_spec("task-a")])

    engine.graceful_shutdown()  # must not raise
    with pytest.raises(SystemExit) as excinfo:
        engine.graceful_shutdown(exit_process=True)

    assert excinfo.value.code == 0
