"""Tests for ``python3 cli.py check`` and the read-only checks module.

Every test passes ``maintainer_root`` and ``env`` explicitly (INV-4) so an
ambient repo-root ``config.json`` or a developer's real ``MAINTAINER_*``
variables can never change the outcome. Process-level rows whitelist the
subprocess environment for the same reason.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

from ai_maintainer import checks

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


def _task_dir(project_root: Path, task_id: str, signal: str | None, mtime: float | None) -> Path:
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


def _by_id(results: list[checks.CheckResult], check_id: str) -> checks.CheckResult:
    for result in results:
        if result.check_id == check_id:
            return result
    raise AssertionError(f"missing check id {check_id}: {[r.check_id for r in results]}")


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


# --- process-level rows ----------------------------------------------------


def test_check_is_standalone_subcommand(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    env = _base_env(MAINTAINER_ROOT=maintainer_root)

    help_run = _run_cli(["--help"], env)
    assert help_run.returncode == 0
    assert "check" in help_run.stdout

    proc = _run_cli(["check"], env)
    assert proc.returncode == 0, proc.stderr
    assert "check:" in proc.stdout
    assert "[PASS] paths.maintainer_root" in proc.stdout
    assert "usage:" not in proc.stderr.lower()


def test_check_exits_zero_when_all_checks_pass(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    _write_json(
        maintainer_root / "config.json",
        {
            "primary_cli": "pi",
            "primary_model": "qwen2.5:free",
            "secondary_cli": "pi",
            "secondary_model": "llama3:free",
        },
    )
    project_root = tmp_path / "project"
    project_root.mkdir()
    _task_dir(project_root, "TASK-1", "In Progress", None)

    env = _base_env(
        MAINTAINER_ROOT=maintainer_root,
        MAINTAINER_DEFAULT_PROJECT_ROOT=project_root,
    )
    proc = _run_cli(["check"], env)

    assert proc.returncode == 0, proc.stderr
    assert "0 failed" in proc.stdout
    assert "[PASS] framework.orchestrator" in proc.stdout
    assert "[PASS] models.primary_model" in proc.stdout
    assert "[PASS] models.secondary_model" in proc.stdout
    assert proc.stderr == ""


def test_check_exits_one_when_maintainer_root_missing(tmp_path: Path) -> None:
    missing = tmp_path / "does-not-exist"
    proc = _run_cli(["check"], _base_env(MAINTAINER_ROOT=missing))

    assert proc.returncode == 1
    assert "maintainer_root: not a directory:" in proc.stderr
    assert str(missing) in proc.stderr


# --- unit-level rows -------------------------------------------------------


def test_check_skips_unset_optional_paths(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()

    results, status = checks.run_checks(
        maintainer_root=maintainer_root, env=_base_env(), now=time.time()
    )

    assert _by_id(results, "paths.ai_team_root").status == checks.STATUS_SKIP
    assert _by_id(results, "paths.default_project_root").status == checks.STATUS_SKIP
    assert _by_id(results, "paths.ai_team_root").detail == "not configured"
    assert status.orchestrator == "unknown"
    assert checks.exit_code(results) == 0


def test_check_reports_invalid_ai_team_root(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    not_a_dir = tmp_path / "ai_team.txt"
    not_a_dir.write_text("not a directory\n", encoding="utf-8")

    results, _status = checks.run_checks(
        maintainer_root=maintainer_root,
        env=_base_env(MAINTAINER_AI_TEAM_ROOT=not_a_dir),
        now=time.time(),
    )

    row = _by_id(results, "paths.ai_team_root")
    assert row.status == checks.STATUS_FAIL
    assert row.detail == f"ai_team_root: not a directory: {not_a_dir}"
    assert checks.exit_code(results) == 1


def test_check_flags_paid_primary_model_for_pi(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    _write_json(
        maintainer_root / "config.json",
        {"primary_cli": "pi", "primary_model": "gpt-4o"},
    )

    results, _status = checks.run_checks(
        maintainer_root=maintainer_root, env=_base_env(), now=time.time()
    )

    row = _by_id(results, "models.primary_model")
    assert row.status == checks.STATUS_FAIL
    assert row.detail == "primary_model: not a free model for cli=pi: gpt-4o"
    assert checks.exit_code(results) == 1


def test_check_flags_paid_secondary_model_for_pi(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    _write_json(
        maintainer_root / "config.json",
        {"secondary_cli": "pi", "secondary_model": "gpt-4o"},
    )

    results, _status = checks.run_checks(
        maintainer_root=maintainer_root, env=_base_env(), now=time.time()
    )

    row = _by_id(results, "models.secondary_model")
    assert row.status == checks.STATUS_FAIL
    assert row.detail == "secondary_model: not a free model for cli=pi: gpt-4o"
    assert checks.exit_code(results) == 1


def test_check_ignores_model_tier_when_cli_not_pi(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    _write_json(
        maintainer_root / "config.json",
        {"primary_cli": "claude", "primary_model": "gpt-4o"},
    )

    results, _status = checks.run_checks(
        maintainer_root=maintainer_root, env=_base_env(), now=time.time()
    )

    row = _by_id(results, "models.primary_model")
    assert row.status == checks.STATUS_SKIP
    assert "cli not pi" in row.detail
    assert "tier not checked" in row.detail


def test_check_unknown_framework_status_warns(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()

    results, status = checks.run_checks(
        maintainer_root=maintainer_root, env=_base_env(), now=time.time()
    )

    row = _by_id(results, "framework.orchestrator")
    assert row.status == checks.STATUS_WARN
    assert row.detail.startswith("unknown")
    assert status.orchestrator == "unknown"
    assert status.source == checks.SOURCE_NONE
    assert "unknown (n/a queued, n/a active, last completed none)" in checks.format_report(
        results, status
    )
    assert checks.exit_code(results) == 0


def test_check_reports_framework_down_and_counters(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    project_root = tmp_path / "project"
    project_root.mkdir()
    now = time.time()
    _task_dir(project_root, "TASK-STALE", "In Progress", now - 2000.0)

    results, status = checks.run_checks(
        maintainer_root=maintainer_root,
        env=_base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=project_root),
        now=now,
    )

    row = _by_id(results, "framework.orchestrator")
    assert row.status == checks.STATUS_FAIL
    assert row.detail == "framework: orchestrator down (no heartbeat within 900.0s)"
    assert status.active_tasks == 1
    assert status.queue_depth == 0
    report = checks.format_report(results, status)
    assert "0 queued, 1 active, last completed none" in report
    assert checks.exit_code(results) == 1


def test_check_reports_queue_state_and_last_completed_task(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    project_root = tmp_path / "project"
    project_root.mkdir()
    now = time.time()
    _task_dir(project_root, "TASK-DONE", "Done", now - 50.0)
    queued_dir = project_root / "active_task" / "TASK-QUEUED"
    queued_dir.mkdir(parents=True)

    results, status = checks.run_checks(
        maintainer_root=maintainer_root,
        env=_base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=project_root),
        now=now,
    )

    assert status.queue_state == "busy"
    assert status.queue_depth == 1
    assert status.active_tasks == 0
    assert status.last_completed_task == "TASK-DONE"
    report = checks.format_report(results, status)
    assert "framework.queue" in report
    assert "busy (1 queued, 0 active, last completed TASK-DONE)" in report

    idle_project = tmp_path / "idle_project"
    (idle_project / "active_task").mkdir(parents=True)
    _results_idle, status_idle = checks.run_checks(
        maintainer_root=maintainer_root,
        env=_base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=idle_project),
        now=now,
    )
    assert status_idle.queue_state == "idle"
    assert status_idle.queue_depth == 0


def test_check_reports_config_consistency_error(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    _write_json(maintainer_root / "config.json", {"bogus": 1})

    results, status = checks.run_checks(
        maintainer_root=maintainer_root, env=_base_env(), now=time.time()
    )

    row = _by_id(results, "config.consistency")
    assert row.status == checks.STATUS_FAIL
    assert "unknown config key(s) in config.json" in row.detail
    assert "bogus" in row.detail
    report = checks.format_report(results, status)
    assert "[FAIL] config.consistency" in report
    assert "check: " in report


def test_check_does_not_mutate_state(tmp_path: Path) -> None:
    maintainer_root = tmp_path / "maintainer"
    maintainer_root.mkdir()
    _write_json(
        maintainer_root / "config.json",
        {"primary_cli": "pi", "primary_model": "qwen2.5:free"},
    )
    project_root = tmp_path / "project"
    project_root.mkdir()
    _task_dir(project_root, "TASK-1", "In Progress", time.time() - 10.0)

    before = _snapshot(tmp_path)
    results, status = checks.run_checks(
        maintainer_root=maintainer_root,
        env=_base_env(MAINTAINER_DEFAULT_PROJECT_ROOT=project_root),
        now=time.time(),
    )
    after = _snapshot(tmp_path)

    assert status.orchestrator == "up"
    assert after == before
    assert set(after) == set(before)
    assert checks.exit_code(results) == 0

# --- ``cli.py stop`` (UTILTOOLS-76 REQ-06 / DOD-09) --------------------------


def test_stop_removes_stale_markers(tmp_path: Path) -> None:
    """A dead pid's markers are removed and the CLI exits 0 (DOD-09)."""

    state_root = tmp_path / "data"
    state_root.mkdir()
    # ``subprocess.run`` has already reaped the child, so this pid is free.
    probe = subprocess.Popen([sys.executable, "-c", "pass"])
    dead_pid = probe.pid
    probe.wait()

    (state_root / "maintainer.pid").write_text(f"{dead_pid}\n", encoding="utf-8")
    (state_root / "maintainer.pid.tmp").write_text("", encoding="utf-8")
    (state_root / "maintainer.heartbeat").write_text("", encoding="utf-8")
    (state_root / "maintainer.heartbeat.tmp").write_text("", encoding="utf-8")

    result = _run_cli(
        ["stop", "--maintainer-root", str(tmp_path), "--state-root", str(state_root)],
        _base_env(),
    )

    assert result.returncode == 0, result.stderr
    assert f"removed stale runtime markers for dead pid {dead_pid}" in result.stdout
    assert not (state_root / "maintainer.pid").exists()
    assert not (state_root / "maintainer.pid.tmp").exists()
    assert not (state_root / "maintainer.heartbeat").exists()
    assert not (state_root / "maintainer.heartbeat.tmp").exists()


def test_stop_rejects_invalid_pid_file(tmp_path: Path) -> None:
    """A non-integer pid file exits 1 and never signals anything (DOD-09)."""

    state_root = tmp_path / "data"
    state_root.mkdir()
    (state_root / "maintainer.pid").write_text("not-a-pid\n", encoding="utf-8")

    result = _run_cli(["stop", "--state-root", str(state_root)], _base_env())

    assert result.returncode == 1
    assert "invalid maintainer pid file" in result.stderr
    assert (state_root / "maintainer.pid").exists()


def test_stop_rejects_non_finite_timeout(tmp_path: Path) -> None:
    """SHOULD_FIX F4: ``--timeout inf``/``nan`` is refused by the parser, so the
    marker poll can never become an unbounded wait (engine parity: ValueError)."""

    state_root = tmp_path / "data"
    state_root.mkdir()

    for bad in ("inf", "-inf", "nan"):
        # ``=`` form: argparse would otherwise read ``-inf`` as an option flag.
        result = _run_cli(
            ["stop", "--state-root", str(state_root), f"--timeout={bad}"], _base_env()
        )
        assert result.returncode == 2, (bad, result.stdout, result.stderr)
        assert "timeout must be a finite number of seconds" in result.stderr


def test_stop_reports_overflowing_pid_without_traceback(tmp_path: Path) -> None:
    """SHOULD_FIX F5: a 26-digit pid file fails ``os.kill`` with OverflowError;
    the CLI reports the malformed file and exits 1 instead of crashing."""

    state_root = tmp_path / "data"
    state_root.mkdir()
    (state_root / "maintainer.pid").write_text("1" * 26 + "\n", encoding="utf-8")

    result = _run_cli(["stop", "--state-root", str(state_root)], _base_env())

    assert result.returncode == 1
    assert "invalid maintainer pid file" in result.stderr
    assert "Traceback" not in result.stderr
    assert (state_root / "maintainer.pid").exists()


def test_stop_is_a_no_op_without_a_pid_file(tmp_path: Path) -> None:
    """Nothing running means nothing to clean up: exit 0, no state mutated."""

    state_root = tmp_path / "data"
    state_root.mkdir()

    result = _run_cli(["stop", "--state-root", str(state_root)], _base_env())

    assert result.returncode == 0
    assert "maintainer not running" in result.stdout
    assert list(state_root.iterdir()) == []
