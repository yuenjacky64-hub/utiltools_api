"""Cross-store task state consistency and audit-safe auto-recovery.

Covers the brief's five test families (REQ-05, DOD-01 .. DOD-12): a consistent
task is a no-op, partial drift aligns the signal to the progress log, terminal
drift aligns to ``hist_tasks``/``dead_tasks``, a manual override refuses
recovery, and a mid-signal crash (the sigterm case) no longer leaves permanent
drift behind.

Every test builds its own store layout under ``tmp_path`` and drives the
service with an injected clock, so nothing here touches the real
``/home/aiteam/ai_team/data`` tree and no test sleeps.
"""

from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

from ai_maintainer.api import CONSISTENCY_CHECK_PATH, build_handler
from ai_maintainer.audit import PHASE_FAILED, PHASE_PENDING, PHASE_REFUSED, OperationType
from ai_maintainer.consistency import (
    ACTION_ALIGN_SIGNAL_TO_PROGRESS,
    ACTION_ALIGN_TO_ARCHIVE,
    ACTION_NOOP,
    ACTION_REFUSED_MANUAL_OVERRIDE,
    CLASS_CONSISTENT,
    CLASS_PROGRESS_SIGNAL_DRIFT,
    CLASS_TERMINAL_ARCHIVE_DRIFT,
    MANUAL_OVERRIDE_FILE,
    PROGRESS_FILES,
    SIGNAL_FILE,
    STATUS_CONSISTENT,
    STATUS_INCONSISTENT,
    ConsistencyService,
    StoreRoots,
    UnknownTaskError,
    read_snapshot,
    run_startup_recovery,
    state_for_token,
)
from ai_maintainer.state_signal_impl import (
    CODE_ACCEPTED,
    CODE_ALREADY_ARCHIVED,
    CODE_MANUAL_OVERRIDE,
    CODE_TERMINAL_MISMATCH,
    CODE_UNSUPPORTED_SIGNAL,
    validate_transition,
)


class FrozenClock:
    """Clock that only moves when a test moves it."""

    def __init__(self, now: float = 1000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now


TASK = "UTILTOOLS-901"
TASK_PROGRESS_FILE = PROGRESS_FILES[0]
OTHER = "UTILTOOLS-902"


# --------------------------------------------------------------------------- #
# Layout helpers: one temp ``active_task`` root plus its sibling stores
# --------------------------------------------------------------------------- #


@pytest.fixture()
def roots(tmp_path: Path) -> StoreRoots:
    state_root = tmp_path / "active_task"
    state_root.mkdir()
    return StoreRoots.for_state_root(state_root)


@pytest.fixture()
def clock() -> FrozenClock:
    return FrozenClock()


@pytest.fixture()
def service(roots: StoreRoots, clock: FrozenClock) -> ConsistencyService:
    return ConsistencyService(roots, clock=clock)


def task_dir(roots: StoreRoots, task: str = TASK) -> Path:
    assert roots.state_root is not None
    path = roots.state_root / task
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_signal(roots: StoreRoots, token: str, task: str = TASK) -> Path:
    path = task_dir(roots, task) / SIGNAL_FILE
    path.write_text(token + "\n", encoding="utf-8")
    return path


def write_progress(roots: StoreRoots, line: str, task: str = TASK) -> Path:
    path = task_dir(roots, task) / TASK_PROGRESS_FILE
    path.write_text(f"step=started\n{line}\n", encoding="utf-8")
    return path


def write_timeline(roots: StoreRoots, state: str, task: str = TASK) -> Path:
    assert roots.timeline_path is not None
    roots.timeline_path.parent.mkdir(parents=True, exist_ok=True)
    with roots.timeline_path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"task": task, "state": state}) + "\n")
    return roots.timeline_path


def archive_task(roots: StoreRoots, tree: str = "hist_tasks", task: str = TASK) -> Path:
    archive_root = dict(roots.archive_roots)[tree]
    path = archive_root / task
    path.mkdir(parents=True, exist_ok=True)
    return path


def journal(roots: StoreRoots) -> list[dict]:
    path = roots.journal_path
    assert path is not None
    if not path.is_file():
        return []
    entries = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            entries.append(json.loads(line))
    return entries


def signal_bytes(roots: StoreRoots, task: str = TASK) -> bytes:
    return (task_dir(roots, task) / SIGNAL_FILE).read_bytes()


# --------------------------------------------------------------------------- #
# Family 1: consistent state is a no-op (REQ-01 / DOD-01, DOD-02)
# --------------------------------------------------------------------------- #


def test_consistent_state_reports_noop(roots, service):
    write_progress(roots, "running")
    write_signal(roots, "Running 1")

    entry = service.report(task=TASK)["tasks"][0]

    assert entry["status"] == STATUS_CONSISTENT
    assert entry["class"] == CLASS_CONSISTENT
    assert entry["recovery"]["action"] == ACTION_NOOP
    assert entry["canonical_source"] == "progress"


def test_consistent_state_is_not_reported_by_aggregate(roots, service):
    write_progress(roots, "running")
    write_signal(roots, "Running 1")

    report = service.report()

    assert report["status"] == STATUS_CONSISTENT
    assert report["task_count"] == 1
    assert report["inconsistent_count"] == 0
    assert report["stores"] == ["archive", "signal", "progress", "timeline"]


def test_queued_slot_without_signal_is_consistent(roots, service):
    task_dir(roots)

    entry = service.report(task=TASK)["tasks"][0]

    assert entry["status"] == STATUS_CONSISTENT
    assert entry["stores"]["signal"] == {"present": False, "state": None}


def test_consistent_task_recovers_to_zero_writes(roots, service):
    write_progress(roots, "running")
    write_signal(roots, "Running 1")
    before = signal_bytes(roots)

    summary = service.recover_all()

    assert summary["recovered"] == 0
    assert summary["inconsistent"] == 0
    assert signal_bytes(roots) == before
    assert journal(roots) == []


# --------------------------------------------------------------------------- #
# Family 2: partially inconsistent state aligns progress + signal (REQ-01/02)
# --------------------------------------------------------------------------- #


def test_progress_signal_drift_is_classified(roots, service):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")

    entry = service.report(task=TASK)["tasks"][0]

    assert entry["status"] == STATUS_INCONSISTENT
    assert entry["class"] == CLASS_PROGRESS_SIGNAL_DRIFT
    assert entry["recovery"]["action"] == ACTION_ALIGN_SIGNAL_TO_PROGRESS
    assert entry["canonical"] == "COMPLETED"
    assert entry["canonical_source"] == "progress"
    assert entry["evidence"]["signal_head"] == "Running 1"


def test_partial_drift_repair_aligns_signal_to_progress(roots, service):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")

    summary = service.recover_all()

    assert summary["inconsistent"] == 1
    assert summary["recovered"] == 1
    assert (task_dir(roots) / SIGNAL_FILE).read_text(encoding="utf-8") == "Done\n"
    # The progress log and the absent timeline are never rewritten by a repair.
    assert "Done 5" in (task_dir(roots) / TASK_PROGRESS_FILE).read_text(encoding="utf-8")
    assert not (roots.timeline_path).exists()
    assert service.report(task=TASK)["status"] == STATUS_CONSISTENT


def test_timeline_drift_is_aligned_to_the_timeline(roots, service):
    write_timeline(roots, "COMPLETED")
    write_signal(roots, "Running 1")

    entry = service.report(task=TASK)["tasks"][0]

    assert entry["canonical_source"] == "timeline"
    assert entry["recovery"]["action"] == ACTION_ALIGN_SIGNAL_TO_PROGRESS

    service.recover_all()

    assert (task_dir(roots) / SIGNAL_FILE).read_text(encoding="utf-8") == "Done\n"


def test_repair_is_idempotent(roots, service):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")
    first = service.recover_all()

    second = service.recover_all()

    assert first["recovered"] == 1
    assert second["recovered"] == 0
    assert second["inconsistent"] == 0
    assert journal(roots) is not None


# --------------------------------------------------------------------------- #
# Family 3: fully terminal inconsistency aligns to hist_tasks (REQ-02)
# --------------------------------------------------------------------------- #


def test_terminal_archive_drift_aligns_to_hist_tasks(roots, service):
    archive_task(roots, "hist_tasks")
    write_progress(roots, "running")
    write_signal(roots, "Running 1")

    entry = service.report(task=TASK)["tasks"][0]

    assert entry["status"] == STATUS_INCONSISTENT
    assert entry["class"] == CLASS_TERMINAL_ARCHIVE_DRIFT
    assert entry["recovery"]["action"] == ACTION_ALIGN_TO_ARCHIVE
    assert entry["canonical"] == "COMPLETED"
    assert entry["canonical_source"] == "archive"

    service.recover_all()

    assert (task_dir(roots) / SIGNAL_FILE).read_text(encoding="utf-8") == "Done\n"


def test_dead_tasks_archive_maps_to_error(roots, service):
    archive_task(roots, "dead_tasks")
    write_signal(roots, "Running 1")

    service.recover_all()

    assert (task_dir(roots) / SIGNAL_FILE).read_text(encoding="utf-8") == "Error\n"
    assert service.report(task=TASK)["status"] == STATUS_CONSISTENT


def test_symlinked_slot_is_reported_but_never_rewritten(roots, service, tmp_path):
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / SIGNAL_FILE).write_text("Running 1\n", encoding="utf-8")
    (outside / TASK_PROGRESS_FILE).write_text("Done 5\n", encoding="utf-8")
    assert roots.state_root is not None
    (roots.state_root / TASK).symlink_to(outside)

    summary = service.recover_all()

    assert summary["recovered"] == 0
    assert summary["tasks"][0]["reason"] == "skipped_symlink"
    assert (outside / SIGNAL_FILE).read_text(encoding="utf-8") == "Running 1\n"


# --------------------------------------------------------------------------- #
# Family 4: recovery refusal when a manual override is indicated (REQ-02/REQ-04)
# --------------------------------------------------------------------------- #


def test_manual_override_refuses_recovery(roots, service):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")
    (task_dir(roots) / MANUAL_OVERRIDE_FILE).write_text("", encoding="utf-8")
    before = signal_bytes(roots)

    summary = service.recover_all()

    assert summary["recovered"] == 0
    assert summary["refused"] == 1
    assert summary["tasks"][0]["action"] == ACTION_REFUSED_MANUAL_OVERRIDE
    assert summary["tasks"][0]["reason"] == "manual_override"
    assert signal_bytes(roots) == before
    assert service.report(task=TASK)["status"] == STATUS_INCONSISTENT


def test_manual_override_refusal_is_journalled_as_refused(roots, service):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")
    (task_dir(roots) / MANUAL_OVERRIDE_FILE).write_text("", encoding="utf-8")

    service.recover_all()

    entries = journal(roots)
    assert len(entries) == 1
    assert entries[0]["phase"] == PHASE_REFUSED
    assert entries[0]["action"] == ACTION_REFUSED_MANUAL_OVERRIDE
    assert entries[0]["op"] == OperationType.CONSISTENCY_RECOVERY.value
    assert entries[0]["before"]["signal_head"] == "Running 1"


def test_failed_write_leaves_store_intact(roots, service, monkeypatch):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")
    before = signal_bytes(roots)

    def boom(self, task_id, token):  # noqa: ANN001 - monkeypatched seam
        raise OSError("disk full")

    monkeypatch.setattr(ConsistencyService, "_write_signal", boom)

    summary = service.recover_all()

    assert summary["failed"] == 1
    assert summary["recovered"] == 0
    assert signal_bytes(roots) == before
    phases = [entry["phase"] for entry in journal(roots)]
    assert phases == [PHASE_PENDING, PHASE_FAILED]


# --------------------------------------------------------------------------- #
# Family 5: audit journal shape (REQ-04 / DOD-05, DOD-09)
# --------------------------------------------------------------------------- #


def test_recovery_journal_records_operation_type_and_evidence(roots, service):
    archive_task(roots, "hist_tasks")
    write_progress(roots, "running")
    write_signal(roots, "Running 1")

    service.recover_all()

    entries = journal(roots)
    assert [entry["phase"] for entry in entries] == ["PENDING", "SUCCEEDED"]
    pending, succeeded = entries
    assert {entry["op"] for entry in entries} == {
        OperationType.CONSISTENCY_RECOVERY.value
    }
    assert pending["entry_id"] == succeeded["entry_id"]
    assert pending["action"] == ACTION_ALIGN_TO_ARCHIVE
    # The triggering signal is preserved verbatim as evidence.
    assert pending["before"]["signal_head"] == "Running 1"
    assert pending["before"]["archive"] == "hist_tasks"
    assert succeeded["after"]["signal_head"] == "Done"


def test_pending_journal_line_precedes_every_store_write(roots, service, monkeypatch):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")
    observed: list[list[str]] = []
    original = ConsistencyService._write_signal

    def spy(self, task_id, token):  # noqa: ANN001 - monkeypatched seam
        observed.append([entry["phase"] for entry in journal(roots)])
        original(self, task_id, token)

    monkeypatch.setattr(ConsistencyService, "_write_signal", spy)

    service.recover_all()

    assert observed == [[PHASE_PENDING]]


# --------------------------------------------------------------------------- #
# Signal middleware gate (REQ-03 / DOD-04, DOD-12)
# --------------------------------------------------------------------------- #


def test_signal_middleware_rejects_archived_task_transition(roots, service):
    archive_task(roots, "hist_tasks")
    write_signal(roots, "Running 1")
    snapshot = read_snapshot(TASK, roots)

    decision = validate_transition(
        TASK, "Running 2\n", snapshot=snapshot, audit=service.audit
    )

    assert decision.accepted is False
    assert decision.code == CODE_ALREADY_ARCHIVED
    assert decision.canonical == "COMPLETED"


def test_rejected_signal_leaves_stores_byte_identical(roots, service):
    archive_task(roots, "hist_tasks")
    write_signal(roots, "Running 1")
    before = signal_bytes(roots)
    snapshot = read_snapshot(TASK, roots)

    validate_transition(TASK, "Running 2\n", snapshot=snapshot, audit=service.audit)

    assert signal_bytes(roots) == before
    entries = journal(roots)
    assert [entry["phase"] for entry in entries] == [PHASE_REFUSED]
    assert entries[0]["op"] == OperationType.SIGNAL_REJECTED.value
    assert entries[0]["before"]["incoming"] == "Running 2"


def test_rejected_signal_is_recorded_as_a_finding(roots, service):
    archive_task(roots, "dead_tasks")
    write_signal(roots, "Running 1")
    snapshot = read_snapshot(TASK, roots)
    decision = validate_transition(TASK, "Done\n", snapshot=snapshot)

    assert decision.code == CODE_TERMINAL_MISMATCH
    service.record_finding({"code": decision.code, "task": decision.task})

    report = service.report(task=TASK)
    assert report["findings"][0]["code"] == CODE_TERMINAL_MISMATCH


def test_signal_middleware_accepts_matching_terminal_token(roots, service):
    archive_task(roots, "hist_tasks")
    write_signal(roots, "Running 1")
    snapshot = read_snapshot(TASK, roots)

    decision = validate_transition(TASK, "Done 5\n", snapshot=snapshot)

    assert decision.accepted is True
    assert decision.code == CODE_ACCEPTED
    assert journal(roots) == []


def test_signal_middleware_refuses_manual_override_target(roots, service):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")
    (task_dir(roots) / MANUAL_OVERRIDE_FILE).write_text("", encoding="utf-8")
    snapshot = read_snapshot(TASK, roots)

    decision = validate_transition(TASK, "In Progress\n", snapshot=snapshot)

    assert decision.accepted is False
    assert decision.code == CODE_MANUAL_OVERRIDE


def test_signal_middleware_rejects_undecodable_token(roots, service):
    write_signal(roots, "Running 1")
    snapshot = read_snapshot(TASK, roots)

    decision = validate_transition(TASK, "gibberish\n", snapshot=snapshot)

    assert decision.accepted is False
    assert decision.code == CODE_UNSUPPORTED_SIGNAL


# --------------------------------------------------------------------------- #
# Startup job + report route (REQ-01, REQ-02 / DOD-01, DOD-03, DOD-06)
# --------------------------------------------------------------------------- #


def test_startup_recovery_repairs_all_detected_drift(roots):
    archive_task(roots, "hist_tasks", task=TASK)
    write_signal(roots, "Running 1", task=TASK)
    write_progress(roots, "Done 5", task=OTHER)
    write_signal(roots, "Running 1", task=OTHER)

    summary = run_startup_recovery(roots)

    assert summary["scanned"] == 2
    assert summary["inconsistent"] == 2
    assert summary["recovered"] == 2
    assert summary["failed"] == 0
    assert summary["journal"] == str(roots.journal_path)
    assert (task_dir(roots) / SIGNAL_FILE).read_text(encoding="utf-8") == "Done\n"
    assert ConsistencyService(roots).report()["status"] == STATUS_CONSISTENT


def test_startup_recovery_without_stores_is_a_noop():
    summary = run_startup_recovery(StoreRoots())

    assert summary["scanned"] == 0
    assert summary["recovered"] == 0
    assert summary["journal"] is None


def test_sigterm_no_longer_causes_state_drift(roots, service):
    # A role is mid-signal when the process is killed: the signal still says
    # RUNNING while the retire path has already archived the task.
    write_progress(roots, "running")
    write_signal(roots, "Running 1")
    archive_task(roots, "hist_tasks")
    assert service.report(task=TASK)["status"] == STATUS_INCONSISTENT

    run_startup_recovery(roots)

    # Next start: the drift is gone and a re-run is a no-op, so no role can be
    # skipped or looped on the stale RUNNING token.
    assert service.report(task=TASK)["status"] == STATUS_CONSISTENT
    assert service.recover_all()["recovered"] == 0


def test_report_route_is_read_only(roots, service):
    archive_task(roots, "hist_tasks")
    write_signal(roots, "Running 1")
    before = signal_bytes(roots)

    report = service.report()

    assert report["task_count"] == 1
    assert report["inconsistent_count"] == 1
    assert signal_bytes(roots) == before
    assert journal(roots) == []


def test_report_of_unknown_task_raises(roots, service):
    write_signal(roots, "Running 1")

    with pytest.raises(UnknownTaskError):
        service.report(task="UTILTOOLS-404")


def test_consistency_check_endpoint_serves_the_report(roots, service):
    write_progress(roots, "Done 5")
    write_signal(roots, "Running 1")
    server = ThreadingHTTPServer(("127.0.0.1", 0), build_handler(None, consistency=service))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        host, port = server.server_address[:2]
        with urllib.request.urlopen(
            f"http://{host}:{port}{CONSISTENCY_CHECK_PATH}"
        ) as response:
            assert response.status == 200
            payload = json.loads(response.read().decode("utf-8"))
        assert payload["path"] == CONSISTENCY_CHECK_PATH
        assert payload["status"] == STATUS_INCONSISTENT
        assert payload["tasks"][0]["task"] == TASK
        assert signal_bytes(roots) == b"Running 1\n"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_consistency_check_endpoint_404s_for_unknown_task(roots, service):
    write_signal(roots, "Running 1")
    server = ThreadingHTTPServer(("127.0.0.1", 0), build_handler(None, consistency=service))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        host, port = server.server_address[:2]
        url = f"http://{host}:{port}{CONSISTENCY_CHECK_PATH}?task=UTILTOOLS-404"
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            urllib.request.urlopen(url)
        assert excinfo.value.code == 404
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_state_for_token_covers_declared_vocabulary():
    assert state_for_token("Done 5").name == "COMPLETED"
    assert state_for_token("In Progress 3").name == "IN_PROGRESS"
    assert state_for_token("Running 1").name == "RUNNING"
    assert state_for_token("gibberish") is None


# --------------------------------------------------------------------------- #
# Family 7: reviewer-v1 findings pinned (v2 redevelopment)
#
# One test per MUST_FIX / SHOULD_FIX / NIT, so a re-appearing defect fails on
# its own finding instead of as a vague drift regression.
# --------------------------------------------------------------------------- #


def _engine_with_task(task: str = TASK, gate=None):
    """Engine whose single curriculum task is ``task`` (nothing is monitored yet)."""

    from ai_maintainer.config import MaintainerConfig
    from ai_maintainer.engine import MaintainerEngine
    from ai_maintainer.tasks.curriculum import Difficulty, TaskCurriculum, TaskSpec

    config = MaintainerConfig(max_steps_per_cycle=4)
    curriculum = TaskCurriculum(
        specs=[
            TaskSpec(
                name=task,
                difficulty=Difficulty.EASY,
                steps_required=4,
                last_proposed_cycle=100,
            )
        ],
        config=config,
        clock=FrozenClock(),
    )
    return MaintainerEngine(
        curriculum, config=config, clock=FrozenClock(), signal_gate=gate
    )


def _get(url: str, method: str = "GET"):
    return urllib.request.urlopen(urllib.request.Request(url, method=method))


def _serve(handler):
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def _stop(server, thread) -> None:
    try:
        server.shutdown()
    finally:
        server.server_close()
        thread.join(timeout=5)


def test_progress_scan_is_a_bounded_tail_not_a_whole_file_read(roots, service):
    """MUST_FIX consistency.py:369 — one huge progress line cannot blow up."""

    from ai_maintainer.consistency import HEAD_LIMIT, PROGRESS_TAIL_LIMIT

    path = task_dir(roots) / TASK_PROGRESS_FILE
    path.write_text(
        "step=started\n" + ("x" * (PROGRESS_TAIL_LIMIT * 3)) + "done\n",
        encoding="utf-8",
    )

    reading = read_snapshot(TASK, roots).progress

    assert reading.present is True
    assert reading.evidence is not None
    assert len(reading.evidence) <= HEAD_LIMIT
    assert path.stat().st_size > PROGRESS_TAIL_LIMIT


def test_progress_report_echoes_capped_evidence(roots, service):
    """MUST_FIX consistency.py:369 — nothing unbounded reaches the HTTP report."""

    from ai_maintainer.consistency import HEAD_LIMIT

    padded = ("y" * 20000) + "running"
    write_progress(roots, padded)
    write_signal(roots, "done")

    snapshot = read_snapshot(TASK, roots)
    assert snapshot.progress.evidence is not None
    assert len(snapshot.progress.evidence) <= HEAD_LIMIT

    payload = service.report(task=TASK)
    progress = payload["tasks"][0]["stores"]["progress"]
    assert "evidence" not in progress
    assert padded not in json.dumps(payload)


def test_signal_gate_rejects_archived_transition_on_the_engine(roots, service):
    """MUST_FIX engine.py:648 — REQ-03 is wired into a real ``run_cycle``."""

    from ai_maintainer.state_signal_impl import build_signal_gate

    archive_task(roots, "hist_tasks")
    write_signal(roots, "Running 1")
    engine = _engine_with_task(gate=build_signal_gate(service))

    result = engine.run_cycle()

    assert result.monitored is None
    rejected = [event for event in engine.events if event["event"] == "SIGNAL_REJECTED"]
    assert rejected and rejected[0]["code"] == CODE_ALREADY_ARCHIVED
    assert rejected[0]["task"] == TASK
    operations = [entry["op"] for entry in journal(roots)]
    assert operations.count(OperationType.SIGNAL_REJECTED.value) == 1
    assert service.findings[0]["code"] == CODE_ALREADY_ARCHIVED
    assert engine._curriculum.specs[0].state.name != "RUNNING"


def test_signal_gate_admits_a_slot_without_a_declared_signal(roots, service):
    """MUST_FIX engine.py:648 — the gate guards transitions, not admission."""

    from ai_maintainer.state_signal_impl import build_signal_gate

    write_progress(roots, "running")
    engine = _engine_with_task(gate=build_signal_gate(service))

    assert engine.run_cycle().monitored == TASK
    assert not [event for event in engine.events if event["event"] == "SIGNAL_REJECTED"]


def test_wire_signal_gate_installs_once_and_keeps_the_first_gate(roots, service):
    """MUST_FIX engine.py:648 — the production wiring seam is exercised."""

    from ai_maintainer.api import wire_signal_gate

    engine = _engine_with_task()
    assert engine.signal_gate is None

    wire_signal_gate(engine, service)
    installed = engine.signal_gate
    assert installed is not None

    wire_signal_gate(engine, service)
    assert engine.signal_gate is installed


def test_default_service_is_shared_across_requests_and_gate(roots, service, monkeypatch):
    """MUST_FIX api.py:138 — the fallback service is one instance, not per request."""

    from ai_maintainer import api
    from ai_maintainer.state_signal_impl import build_signal_gate

    monkeypatch.setattr(api, "_DEFAULT_SERVICE", None)
    monkeypatch.setattr(
        ConsistencyService, "from_environment", classmethod(lambda cls: service)
    )

    assert api.default_consistency_service() is api.default_consistency_service()
    assert api.default_consistency_service() is service

    # A gate rejection recorded on that instance is visible through the route
    # that has no service injected: ``findings[]`` is no longer always empty.
    archive_task(roots, "hist_tasks")
    write_signal(roots, "Running 1")
    engine = _engine_with_task(gate=build_signal_gate(service))
    engine.run_cycle()

    server, thread = _serve(build_handler(engine))
    try:
        host, port = server.server_address[:2]
        with _get(f"http://{host}:{port}{CONSISTENCY_CHECK_PATH}") as response:
            payload = json.loads(response.read().decode("utf-8"))
    finally:
        _stop(server, thread)

    assert payload["findings"][0]["code"] == CODE_ALREADY_ARCHIVED


def test_consistency_route_rejects_non_get_with_allow_header(roots, service):
    """SHOULD_FIX api.py:120 — DOD-02's method contract is now pinned."""

    server, thread = _serve(build_handler(None, consistency=service))
    try:
        host, port = server.server_address[:2]
        url = f"http://{host}:{port}{CONSISTENCY_CHECK_PATH}"
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _get(url, method="POST")
        assert excinfo.value.code == 405
        assert excinfo.value.headers["Allow"] == "GET"
    finally:
        _stop(server, thread)


def test_consistency_route_404s_for_an_unknown_path(roots, service):
    """SHOULD_FIX api.py:120 — the new route did not widen the 404 contract."""

    server, thread = _serve(build_handler(None, consistency=service))
    try:
        host, port = server.server_address[:2]
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _get(f"http://{host}:{port}/api/v1/utils/not-a-route")
        assert excinfo.value.code == 404
    finally:
        _stop(server, thread)


def test_consistency_route_answers_head_like_get(roots, service):
    """NIT api.py:120 — HEAD is answered by the GET route, body suppressed."""

    write_progress(roots, "Done 5")
    server, thread = _serve(build_handler(None, consistency=service))
    try:
        host, port = server.server_address[:2]
        url = f"http://{host}:{port}{CONSISTENCY_CHECK_PATH}"
        with _get(url, method="HEAD") as response:
            assert response.status == 200
            assert response.read() == b""
    finally:
        _stop(server, thread)


def test_signal_head_journal_evidence_is_capped(roots, service):
    """SHOULD_FIX state_signal_impl.py:101 — the journal stays bounded."""

    from ai_maintainer.state_signal_impl import INCOMING_LIMIT

    write_signal(roots, "gibberish " + ("z" * (INCOMING_LIMIT * 5)))
    snapshot = read_snapshot(TASK, roots)

    decision = validate_transition(
        TASK,
        snapshot.signal.evidence or "",
        snapshot=snapshot,
        audit=service.audit,
    )

    assert decision.code == CODE_UNSUPPORTED_SIGNAL
    head = decision.evidence["signal_head"]
    assert head is not None and len(head) <= INCOMING_LIMIT
    before = journal(roots)[0]["before"]
    assert all(
        not isinstance(value, str) or len(value) <= INCOMING_LIMIT
        for value in before.values()
    )


def test_findings_buffer_is_bounded(roots, service):
    """SHOULD_FIX consistency.py:635 — memory and response amplification capped."""

    from ai_maintainer.consistency import FINDINGS_MAXLEN, HEAD_LIMIT

    for index in range(FINDINGS_MAXLEN + 5):
        service.record_finding(
            {
                "code": CODE_ALREADY_ARCHIVED,
                "task": f"T-{index}",
                "detail": "d" * 5000,
            }
        )

    findings = service.findings
    assert len(findings) == FINDINGS_MAXLEN
    assert findings[-1]["task"] == f"T-{FINDINGS_MAXLEN + 4}"
    assert all(len(finding["detail"]) <= HEAD_LIMIT for finding in findings)
    assert len(service.report()["findings"]) == FINDINGS_MAXLEN


def test_repair_refuses_a_symlink_swapped_into_the_slot(roots, service, tmp_path):
    """SHOULD_FIX consistency.py:779 — the write re-checks the slot it opens."""

    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / SIGNAL_FILE).write_text("Running 1\n", encoding="utf-8")
    assert roots.state_root is not None
    (roots.state_root / TASK).symlink_to(outside)

    with pytest.raises(OSError):
        service._write_signal(TASK, "Done")

    assert (outside / SIGNAL_FILE).read_text(encoding="utf-8") == "Running 1\n"
    assert not (outside / f"{SIGNAL_FILE}.tmp").exists()


def test_report_omits_absolute_store_paths(roots, service):
    """NIT consistency.py:676 — the endpoint contract is what §4 asked for."""

    write_progress(roots, "Done 5")

    report = service.report()

    assert "roots" not in report
    assert str(roots.state_root) not in json.dumps(report)


def test_token_for_state_only_offers_terminal_tokens():
    """NIT consistency.py:190 — a repair never forges an active generation."""

    from ai_maintainer.consistency import TaskState, token_for_state

    # Active generations are not writable evidence: a repair must never invent
    # a generation number the orchestrator never issued.
    assert token_for_state(TaskState.RUNNING) is None
    assert token_for_state(TaskState.IN_PROGRESS) is None
    assert token_for_state(TaskState.PENDING) is None
    assert token_for_state("gibberish") is None
    # Terminal evidence is writable and round-trips through ``state_for_token``.
    for state, token in (
        (TaskState.COMPLETED, "Done"),
        (TaskState.FAILED, "Error"),
        (TaskState.CANCELLED, "Rejected"),
    ):
        assert token_for_state(state) == token
        assert state_for_token(token) is state
