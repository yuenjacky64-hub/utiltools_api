"""Tests for ``python3 cli.py status [--json]`` and ``ai_maintainer.status``.

Every row passes ``maintainer_root`` and ``env`` explicitly so an ambient
repo-root ``config.json`` or a developer's real ``MAINTAINER_*`` variables can
never change the outcome; process-level rows whitelist the subprocess
environment for the same reason.
"""

from __future__ import annotations

import dataclasses
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from ai_maintainer import checks, status

REPO_ROOT = Path(__file__).resolve().parents[1]
CLI = REPO_ROOT / "cli.py"


def _base_env(**overrides: object) -> dict[str, str]:
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    env.update({key: str(value) for key, value in overrides.items()})
    return env


def _write_json(path: Path, payload: object) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def _task_dir(
    project_root: Path, task_id: str, signal: str | None, mtime: float | None
) -> Path:
    """Create ``active_task/<id>/`` with an optional signal and a progress log."""

    task_dir = project_root / "active_task" / task_id
    task_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    if signal is not None:
        signal_path = task_dir / "_signal.txt"
        signal_path.write_text(f"{signal}\n", encoding="utf-8")
        written.append(signal_path)
    progress_path = task_dir / "agent_progress.log"
    progress_path.write_text("step=heartbeat\n", encoding="utf-8")
    written.append(progress_path)
    if mtime is not None:
        for path in written:
            os.utime(path, (mtime, mtime))
    return task_dir


def _run_cli(args: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CLI), *args],
        cwd=str(REPO_ROOT),
        env=env,
        capture_output=True,
        text=True,
    )


def _snapshot(root: Path) -> dict[str, tuple[int, int]]:
    snapshot: dict[str, tuple[int, int]] = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(dirnames) + sorted(filenames):
            path = Path(dirpath) / name
            try:
                stat_result = path.stat()
            except OSError:  # pragma: no cover - defensive
                continue
            snapshot[str(path.relative_to(root))] = (
                stat_result.st_size,
                stat_result.st_mtime_ns,
            )
    return snapshot


def _roots(tmp_path: Path) -> tuple[Path, Path]:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    project_root = tmp_path / "project"
    project_root.mkdir()
    return maintainer_root, project_root


def _busy_project(tmp_path: Path, now: float) -> tuple[Path, Path]:
    """A busy project: one done task, two active tasks, one queued task."""

    maintainer_root, project_root = _roots(tmp_path)
    _task_dir(project_root, "TASK-DONE", "Done", now - 500.0)
    _task_dir(project_root, "TASK-A", "In Progress", now - 10.0)
    _task_dir(project_root, "TASK-B", "In Progress", now - 5.0)
    (project_root / "active_task" / "TASK-QUEUED").mkdir(parents=True)
    return maintainer_root, project_root


# --- DOD-01: REQ-01 --------------------------------------------------------


def test_status_subcommand_accepts_json_flag(tmp_path: Path) -> None:
    maintainer_root, _project_root = _roots(tmp_path)
    env = _base_env(MAINTAINER_ROOT=maintainer_root)

    help_run = _run_cli(["status", "--help"], env)
    assert help_run.returncode == 0
    assert "--json" in help_run.stdout
    help_text = " ".join(help_run.stdout.split())  # argparse wraps; normalise
    assert "human-readable summary" in help_text
    assert "one JSON object on stdout instead of text" in help_text

    proc = _run_cli(["status", "--json"], env)
    assert proc.returncode == 0, proc.stderr
    assert proc.stderr == ""

    text_proc = _run_cli(["status"], env)
    assert text_proc.returncode == 0, text_proc.stderr
    assert text_proc.stdout.startswith("maintainer status\n")
    with_text_only = _run_cli(["status"], env)  # no --json still works twice
    assert with_text_only.returncode == 0


# --- DOD-02: REQ-02 --------------------------------------------------------


def test_status_json_stdout_is_single_valid_json_document(tmp_path: Path) -> None:
    maintainer_root, project_root = _busy_project(tmp_path, time.time())
    env = _base_env(
        MAINTAINER_ROOT=maintainer_root,
        MAINTAINER_DEFAULT_PROJECT_ROOT=project_root,
    )

    proc = _run_cli(["status", "--json"], env)

    assert proc.returncode == 0, proc.stderr
    assert proc.stderr == ""
    assert proc.stdout.endswith("\n")
    assert proc.stdout.count("\n") == 1  # exactly one document, nothing else
    payload = json.loads(proc.stdout)
    assert isinstance(payload, dict)
    assert json.loads(status.render_json(payload)) == payload


# --- DOD-03: REQ-02 --------------------------------------------------------


def test_payload_has_required_fields(tmp_path: Path) -> None:
    maintainer_root, project_root = _busy_project(tmp_path, time.time())

    payload = status.build_status_payload(
        maintainer_root=maintainer_root,
        env=_base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=project_root),
        now=time.time(),
    )

    for name in status.REQUIRED_FIELDS:
        assert name in payload, name
    for name in (
        "daemon_status",
        "orchestrator_up",
        "orchestrator_status",
        "active_task_id",
        "queue_state",
        "queue_depth",
        "active_tasks",
        "heartbeat_age_sec",
        "memory",
        "total_cycles",
        "bugs_patched",
        "total_learnings",
        "latest_task_run",
        "generated_at",
        "sources",
        "unavailable",
    ):
        assert name in payload, name

    assert payload["daemon_status"] in {"running", "stale", "unknown"}
    assert payload["orchestrator_status"] in {"up", "down", "unknown"}
    assert isinstance(payload["orchestrator_up"], bool) or payload["orchestrator_up"] is None
    assert payload["queue_state"] in {"idle", "busy", "unknown"}
    assert isinstance(payload["active_task_id"], str)
    for name in ("queue_depth", "active_tasks", *status.COUNTER_FIELDS):
        value = payload[name]
        assert value is None or (isinstance(value, int) and not isinstance(value, bool))
    assert payload["heartbeat_age_sec"] is None or isinstance(
        payload["heartbeat_age_sec"], float
    )
    assert isinstance(payload["generated_at"], float)

    memory = payload["memory"]
    assert isinstance(memory["available"], bool)
    for name in ("rss_kb", "peak_rss_kb"):
        assert memory[name] is None or (
            isinstance(memory[name], int) and not isinstance(memory[name], bool)
        )

    latest = payload["latest_task_run"]
    assert latest is None or isinstance(latest, dict)
    assert set(payload["sources"]) == {
        "orchestrator",
        "counters",
        "latest_task_run",
        "memory",
    }
    assert all(isinstance(value, str) for value in payload["sources"].values())
    assert isinstance(payload["unavailable"], list)
    assert all(isinstance(name, str) for name in payload["unavailable"])


# --- DOD-04: REQ-02 --------------------------------------------------------


def test_payload_values_trace_to_sources(tmp_path: Path) -> None:
    now = time.time()
    maintainer_root, project_root = _busy_project(tmp_path, now)
    _write_json(
        project_root / "active_task" / status.STATUS_DOC_NAME,
        {
            "total_cycles": 12,
            "bugs_patched": 3,
            "total_learnings": 7,
            "latest_task_run": {
                "task_id": "TASK-A",
                "status": "in_progress",
                "completed_age_sec": 42.0,
            },
        },
    )
    env = _base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=project_root)

    payload = status.build_status_payload(
        maintainer_root=maintainer_root, env=env, now=now
    )

    assert payload["daemon_status"] == "running"
    assert payload["orchestrator_up"] is True
    assert payload["orchestrator_status"] == "up"
    assert payload["active_task_id"] == "TASK-B"  # newest non-terminal signal
    assert payload["queue_state"] == "busy"
    assert payload["queue_depth"] == 1
    assert payload["active_tasks"] == 2
    assert payload["total_cycles"] == 12
    assert payload["bugs_patched"] == 3
    assert payload["total_learnings"] == 7
    assert payload["latest_task_run"] == {
        "task_id": "TASK-A",
        "status": "in_progress",
        "completed_age_sec": 42.0,
    }
    assert payload["sources"] == {
        "orchestrator": checks.SOURCE_DISK,
        "counters": status.STATUS_DOC_NAME,
        "latest_task_run": status.STATUS_DOC_NAME,
        "memory": status.SOURCE_THIS_PROCESS,
    }
    assert payload["unavailable"] == []

    # Without the counter document the run block falls back to the disk probe.
    (project_root / "active_task" / status.STATUS_DOC_NAME).unlink()
    fallback = status.build_status_payload(
        maintainer_root=maintainer_root, env=env, now=now
    )
    assert fallback["latest_task_run"]["task_id"] == "TASK-DONE"
    assert fallback["latest_task_run"]["status"] == "done"
    assert fallback["sources"]["latest_task_run"] == checks.SOURCE_DISK
    assert fallback["total_cycles"] is None


# --- DOD-05: REQ-02 --------------------------------------------------------


def test_payload_tolerates_absent_and_malformed_status_document(tmp_path: Path) -> None:
    now = time.time()
    maintainer_root, project_root = _roots(tmp_path)
    env = _base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=project_root)
    doc = project_root / "active_task" / status.STATUS_DOC_NAME

    absent = status.build_status_payload(
        maintainer_root=maintainer_root, env=env, now=now
    )
    assert absent["sources"]["counters"] == status.SOURCE_UNAVAILABLE
    for name in status.COUNTER_FIELDS:
        assert absent[name] is None
    assert absent["latest_task_run"] is None
    assert absent["sources"]["latest_task_run"] == checks.SOURCE_NONE
    assert absent["unavailable"] == [
        "active_task_id",
        "bugs_patched",
        "latest_task_run",
        "total_cycles",
        "total_learnings",
    ]

    for text in ("{not json", "[1, 2, 3]", '"scalar"'):
        doc.parent.mkdir(parents=True, exist_ok=True)
        doc.write_text(text, encoding="utf-8")
        payload = status.build_status_payload(
            maintainer_root=maintainer_root, env=env, now=now
        )
        for name in status.COUNTER_FIELDS:
            assert payload[name] is None, (text, name)
        assert payload["sources"]["counters"] == status.SOURCE_UNAVAILABLE
        assert set(status.COUNTER_FIELDS) <= set(payload["unavailable"])

    # A bool/string/float counter is not a counter either (§4.3: ints only);
    # the document still parses, so counters are read from it as nulls.
    _write_json(doc, {"total_cycles": True, "bugs_patched": "12"})
    bools = status.build_status_payload(
        maintainer_root=maintainer_root, env=env, now=now
    )
    assert bools["total_cycles"] is None
    assert bools["bugs_patched"] is None
    assert bools["sources"]["counters"] == status.STATUS_DOC_NAME
    assert set(status.COUNTER_FIELDS) <= set(bools["unavailable"])

    _write_json(doc, {"total_cycles": "12", "bugs_patched": 3.5})
    coerced = status.build_status_payload(
        maintainer_root=maintainer_root, env=env, now=now
    )
    assert coerced["total_cycles"] is None
    assert coerced["bugs_patched"] is None


# --- DOD-06: REQ-01 --------------------------------------------------------


def test_status_is_read_only_and_never_exits_one(tmp_path: Path) -> None:
    now = time.time()
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    _write_json(
        maintainer_root / "config.json",
        {"primary_cli": "pi", "primary_model": "gpt-4o"},  # a FAIL for check
    )

    up_root = tmp_path / "up"
    _task_dir(up_root, "TASK-UP", "In Progress", now - 5.0)
    down_root = tmp_path / "down"
    _task_dir(down_root, "TASK-STALE", "In Progress", now - 5000.0)
    unknown_root = tmp_path / "unknown"
    unknown_root.mkdir()

    for root, expected in (
        (up_root, "running"),
        (down_root, "stale"),
        (unknown_root, "unknown"),
    ):
        env = _base_env(
            MAINTAINER_ROOT=maintainer_root,
            MAINTAINER_DEFAULT_PROJECT_ROOT=root,
        )
        for extra in ([], ["--json"]):
            before = _snapshot(tmp_path)
            proc = _run_cli(["status", *extra], env)
            after = _snapshot(tmp_path)
            assert proc.returncode == 0, (root, extra, proc.stderr)
            assert proc.returncode != 1
            assert proc.stderr == ""
            assert after == before
            if extra:
                assert json.loads(proc.stdout)["daemon_status"] == expected
            else:
                assert f"daemon: {expected} " in proc.stdout


# --- DOD-07: REQ-01 --------------------------------------------------------


def test_text_mode_renders_from_same_payload(tmp_path: Path, monkeypatch) -> None:
    now = time.time()
    maintainer_root, project_root = _busy_project(tmp_path, now)
    _write_json(
        project_root / "active_task" / status.STATUS_DOC_NAME,
        {
            "total_cycles": 12,
            "bugs_patched": 3,
            "total_learnings": 7,
            "latest_task_run": {
                "task_id": "TASK-A",
                "status": "done",
                "completed_age_sec": 12.0,
            },
        },
    )
    payload = status.build_status_payload(
        maintainer_root=maintainer_root,
        env=_base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=project_root),
        now=now,
    )
    text = status.format_text(payload)

    # All eight DoD-named fields are rendered from the payload (§4.5 layout):
    # daemon_status -> "daemon: running", orchestrator_up -> "orchestrator: up",
    # active_task_id -> "active task: TASK-B", queue_state -> "queue: busy",
    # total_cycles/bugs_patched/total_learnings -> "counters: ..." line,
    # latest_task_run -> "latest task run: TASK-A done (age 12.0s)".
    assert "daemon: running" in text
    assert "orchestrator: up" in text
    assert "active task: TASK-B" in text
    assert "queue: busy (1 queued, 2 active, last completed TASK-DONE)" in text
    assert "counters: total_cycles=12 bugs_patched=3 total_learnings=7" in text
    assert "latest task run: TASK-A done (age 12.0s)" in text
    assert text.startswith("maintainer status\n")
    assert text.splitlines()[-1] == "unavailable: none"
    memory_line = next(line for line in text.splitlines() if line.startswith("memory: "))
    assert memory_line.startswith("memory: rss ") or memory_line == "memory: unavailable"

    def _boom(**_: object) -> None:
        raise AssertionError("format_text must not re-probe")

    monkeypatch.setattr(checks, "run_checks", _boom)
    assert status.format_text(payload) == text  # INV-6: one payload, two renders


# --- DOD-08: REQ-02 --------------------------------------------------------


def test_payload_never_leaks_config_values_or_paths(tmp_path: Path) -> None:
    now = time.time()
    maintainer_root, project_root = _busy_project(tmp_path, now)
    model = "qwen2.5:free"
    _write_json(
        maintainer_root / "config.json",
        {"primary_cli": "pi", "primary_model": model},
    )
    env = _base_env(
        MAINTAINER_DEFAULT_PROJECT_ROOT=project_root,
        MAINTAINER_AI_TEAM_ROOT=str(tmp_path / "ai_team"),
    )

    payload = status.build_status_payload(
        maintainer_root=maintainer_root, env=env, now=now
    )
    rendered = status.render_json(payload)

    assert model not in rendered
    assert str(tmp_path) not in rendered
    assert str(maintainer_root) not in rendered
    assert str(project_root) not in rendered
    assert os.sep + "home" not in rendered
    assert payload["active_task_id"] == "TASK-B"
    assert payload["latest_task_run"]["task_id"] == "TASK-DONE"


# --- DOD-09: REQ-02 --------------------------------------------------------


def test_framework_status_active_task_id_defaults_to_none_for_watcher_source() -> None:
    fields = dataclasses.fields(checks.FrameworkStatus)
    assert fields[-1].name == "active_task_id"
    assert fields[-1].default is None

    positional = checks.FrameworkStatus(
        "up", "idle", 0, 1, "TASK-1", checks.SOURCE_WATCHER, None
    )
    assert positional.active_task_id is None

    from_watcher = checks._status_from_watcher({"orchestrator": "up", "queue_depth": 0})
    assert from_watcher.source == checks.SOURCE_WATCHER
    assert from_watcher.active_task_id is None

    payload = status.build_status_payload(
        maintainer_root=None, env=_base_env(MAINTAINER_ROOT=Path("/nonexistent/root"))
    )
    assert payload["active_task_id"] is None
    assert "active_task_id" in payload["unavailable"]
