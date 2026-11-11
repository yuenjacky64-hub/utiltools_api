"""UTILTOOLS-102: CLI lock preflight / stale-owner / stop-owner tests (REQ-03/REQ-04).

Runs the real CLI in subprocesses against a scratch ``MAINTAINER_ROOT``
(pytest ``tmp_path``), so the repo's own ``data/`` directory and its runtime
markers stay untouched.
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

from ai_maintainer.util.fs import InstanceLock, probe_lock, LOCK_PROBE_HELD

REPO_ROOT = Path(__file__).resolve().parents[1]


def _cli(*args: str, timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(REPO_ROOT / "cli.py"), *args],
        cwd=REPO_ROOT,
        env={**os.environ, "MAINTAINER_ROOT": str(REPO_ROOT)},
        capture_output=True,
        text=True,
        timeout=timeout,
    )


@pytest.fixture()
def scratch_root(tmp_path: Path) -> Path:
    """A scratch maintainer root: markers live under ``<root>/data``.

    Every command in this module is invoked with ``--maintainer-root <root>``
    (no ``--state-root``), so pid / heartbeat / lock land in ``<root>/data``
    and the repo's own ``data/`` is never written to (DOD-08 hygiene).
    """

    (tmp_path / "data").mkdir(parents=True, exist_ok=True)
    return tmp_path


def test_start_on_held_lock_exits_nonzero_without_spawning(
    scratch_root: Path,
) -> None:
    """REQ-03: a held lock makes ``start`` exit 1 without spawning a child."""

    lock = InstanceLock(scratch_root / "data" / "maintainer.lock")
    lock.acquire()
    try:
        result = _cli("start", "--maintainer-root", str(scratch_root), timeout=10.0)
    finally:
        lock.release()
    assert result.returncode == 1, result.stderr
    assert "maintainer already running" in result.stderr
    # preflight refuses before engine construction: no markers were written
    assert not (scratch_root / "data" / "maintainer.pid").exists()
    assert not (scratch_root / "data" / "maintainer.heartbeat").exists()


def test_status_reports_stopped_for_stale_pid_with_free_lock(
    scratch_root: Path,
) -> None:
    """REQ-04: a free lock makes a surviving pid file report STOPPED."""

    pid_path = scratch_root / "data" / "maintainer.pid"
    pid_path.write_text("424242\n", encoding="utf-8")
    result = _cli("status", "--maintainer-root", str(scratch_root))
    assert result.returncode == 0, result.stderr
    assert "STOPPED" in result.stdout
    assert "424242" in result.stdout


def test_stop_signals_the_live_lock_owner(
    scratch_root: Path,
) -> None:
    """REQ-04: ``stop`` signals the process that owns the lock, not the pid
    file — even when the pid file is missing entirely."""

    result = _cli("start", "--maintainer-root", str(scratch_root), timeout=30.0)
    assert result.returncode == 0, result.stderr

    # Locate the daemon child by its lock ownership, not by the pid file.
    deadline = time.monotonic() + 3.0
    winner_pid: int | None = None
    while time.monotonic() < deadline:
        probe = probe_lock(scratch_root / "data" / "maintainer.lock")
        if probe.state == LOCK_PROBE_HELD:
            winner_pid = probe.owner_pid
            break
        time.sleep(0.1)
    assert winner_pid is not None, "daemon never acquired the lock"
    # The pid file may not exist yet; ``stop`` must still find the owner via
    # the lock.
    pid_path = scratch_root / "data" / "maintainer.pid"
    pid_path.unlink(missing_ok=True)

    stop = _cli("stop", "--maintainer-root", str(scratch_root), timeout=30.0)
    assert stop.returncode == 0, stop.stderr
    assert f"maintainer stopped (pid {winner_pid})" in stop.stdout
    assert probe_lock(scratch_root / "data" / "maintainer.lock").state != (
        LOCK_PROBE_HELD
    )


def test_cli_sigkill_owner_then_start_replaces_stale_pid(
    scratch_root: Path,
) -> None:
    """DOD-02: SIGKILL the lock holder; the stale pid survives, the next
    ``start`` acquires the released lock and replaces it — no manual cleanup.
    """

    result = _cli("start", "--maintainer-root", str(scratch_root), timeout=30.0)
    assert result.returncode == 0, result.stderr

    deadline = time.monotonic() + 3.0
    winner_pid: int | None = None
    while time.monotonic() < deadline:
        probe = probe_lock(scratch_root / "data" / "maintainer.lock")
        if probe.state == LOCK_PROBE_HELD:
            winner_pid = probe.owner_pid
            break
        time.sleep(0.1)
    assert winner_pid is not None

    # Wait for the daemon to publish its pid marker, then SIGKILL it: the
    # markers survive, and the kernel drops the flock.
    pid_path = scratch_root / "data" / "maintainer.pid"
    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline and not pid_path.exists():
        time.sleep(0.05)
    assert pid_path.exists()

    # The winner is a detached grandchild, so poll for death instead of
    # ``os.waitpid``.
    os.kill(winner_pid, signal.SIGKILL)
    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline:
        try:
            os.kill(winner_pid, 0)
        except ProcessLookupError:
            break
        time.sleep(0.05)
    stale_pid = pid_path.read_text(encoding="utf-8").strip()
    assert stale_pid == str(winner_pid)
    assert probe_lock(scratch_root / "data" / "maintainer.lock").state != (
        LOCK_PROBE_HELD
    )

    restart = _cli("start", "--maintainer-root", str(scratch_root), timeout=30.0)
    assert restart.returncode == 0, restart.stderr

    deadline = time.monotonic() + 3.0
    successor_pid: int | None = None
    while time.monotonic() < deadline:
        probe = probe_lock(scratch_root / "data" / "maintainer.lock")
        if probe.state == LOCK_PROBE_HELD:
            successor_pid = probe.owner_pid
            break
        time.sleep(0.1)
    assert successor_pid is not None
    assert successor_pid != winner_pid
    new_pid = (scratch_root / "data" / "maintainer.pid").read_text(
        encoding="utf-8"
    ).strip()
    assert new_pid == str(successor_pid)

    stop = _cli("stop", "--maintainer-root", str(scratch_root), timeout=30.0)
    assert stop.returncode == 0, stop.stderr
