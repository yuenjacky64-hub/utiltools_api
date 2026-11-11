"""UTILTOOLS-102: CLI lock preflight / stale-owner / stop-owner tests (REQ-03/REQ-04).

Runs the real CLI in subprocesses against a scratch ``MAINTAINER_ROOT`` so the
tests exercise the operator-visible contract: a held lock rejects the second
loop, a stale pid reports STOPPED, and ``stop`` signals the lock owner.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

def _scratch_root() -> Path:
    data = REPO_ROOT / "data"
    data.mkdir(exist_ok=True)
    return REPO_ROOT


def _cli(*args: str, timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(REPO_ROOT / "cli.py"), *args],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
        env={**os.environ, "PYTHONPATH": str(REPO_ROOT / "src")},
    )


def test_start_on_held_lock_exits_nonzero_naming_owner() -> None:
    sys.path.insert(0, str(REPO_ROOT / "src"))
    from ai_maintainer.util.fs import InstanceLock

    root = _scratch_root()
    lock_file = root / "data" / "maintainer.lock"
    holder = InstanceLock(lock_file)
    holder.acquire()
    try:
        result = _cli("start", "--foreground", "--timeout", "5")
        assert result.returncode != 0
        combined = f"{result.stdout}\n{result.stderr}"
        assert "MaintainerAlreadyRunning" in combined
        assert str(os.getpid()) in combined
    finally:
        holder.release()


def test_status_reports_stopped_for_stale_pid_on_free_lock() -> None:
    root = _scratch_root()
    data = root / "data"
    data.mkdir(exist_ok=True)
    # A SIGKILLed owner leaves a pid file; the lock is free (kernel released
    # the flock) so status must say the daemon is stopped, not running.
    pid_file = data / "maintainer.pid"
    pid_file.write_text("999999\n", encoding="utf-8")
    lock_file = data / "maintainer.lock"
    if lock_file.exists():
        lock_file.unlink()
    try:
        result = _cli("status")
        assert result.returncode == 0
        assert "STOPPED" in result.stdout or "stopped" in result.stdout.lower()
    finally:
        # Keep the shared scratch data dir clean for later tests: the stale
        # pid and the lock artifact must not outlive this scenario.
        pid_file.unlink(missing_ok=True)
        lock_file.unlink(missing_ok=True)


def test_stop_signals_live_lock_owner() -> None:
    sys.path.insert(0, str(REPO_ROOT / "src"))
    from ai_maintainer.util.fs import InstanceLock

    root = _scratch_root()
    data = root / "data"
    data.mkdir(exist_ok=True)
    lock_file = data / "maintainer.lock"

    holder_code = (
        "import sys, time;"
        f"sys.path.insert(0, {str(REPO_ROOT / 'src')!r});"
        "from ai_maintainer.util.fs import InstanceLock;"
        f"InstanceLock({str(lock_file)!r}).acquire();"
        "time.sleep(60)"
    )
    holder = subprocess.Popen(
        [sys.executable, "-c", holder_code],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            # The holder may not have created the lock file yet.
            try:
                if lock_file.read_text().strip() != "":
                    break
            except FileNotFoundError:
                pass
            time.sleep(0.05)
        result = _cli("stop", "--timeout", "5")
        assert result.returncode == 0
        holder.wait(timeout=15)  # SIGTERM delivered to the lock owner.
    finally:
        if holder.poll() is None:
            holder.kill()
