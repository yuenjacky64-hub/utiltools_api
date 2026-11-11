"""UTILTOOLS-102: the process-lifetime instance lock (REQ-01/REQ-02/REQ-03/REQ-04)."""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ai_maintainer.util.fs import (  # noqa: E402
    LOCK_PROBE_FREE,
    LOCK_PROBE_HELD,
    MaintainerAlreadyRunning,
    InstanceLock,
    probe_lock,
    read_owner_pid,
)

REPO_ROOT = Path(__file__).resolve().parents[1]


def _data_dir(tmp_path: Path, name: str) -> Path:
    data = tmp_path / name / "data"
    data.mkdir(parents=True)
    return data


def test_acquire_writes_owner_pid_and_owns(tmp_path: Path) -> None:
    data = _data_dir(tmp_path, "owner")
    lock_file = data / "maintainer.lock"
    lock = InstanceLock(lock_file)
    lock.acquire()
    try:
        assert lock.owns()
        assert read_owner_pid(lock_file) == os.getpid()
    finally:
        lock.release()
    # INV-LOCK-2: releasing never unlinks the lock file.
    assert lock_file.exists()
    assert not lock.owns()


def test_second_acquire_raises_maintainer_already_running(tmp_path: Path) -> None:
    data = _data_dir(tmp_path, "race")
    lock_file = data / "maintainer.lock"
    winner = InstanceLock(lock_file)
    winner.acquire()
    try:
        loser = InstanceLock(lock_file)
        with pytest.raises(MaintainerAlreadyRunning) as excinfo:
            loser.acquire()
        assert excinfo.value.owner_pid == os.getpid()
    finally:
        winner.release()
    # The lock is reusable right after release - no manual cleanup.
    again = InstanceLock(lock_file)
    again.acquire()
    again.release()


def test_probe_lock_free_held_and_stale(tmp_path: Path) -> None:
    data = _data_dir(tmp_path, "probe")
    lock_file = data / "maintainer.lock"

    # No file at all: free.
    probe = probe_lock(lock_file)
    assert probe.state == LOCK_PROBE_FREE

    lock = InstanceLock(lock_file)
    lock.acquire()
    try:
        probe = probe_lock(lock_file)
        assert probe.state == LOCK_PROBE_HELD
        assert probe.owner_pid == os.getpid()
        assert probe.held
    finally:
        lock.release()

    # Stale pid left by a SIGKILLed owner: the kernel released the flock, so a
    # probe must report free and the next acquire must succeed (REQ-04).
    lock_file.write_text(str(os.getpid()), encoding="utf-8")
    assert probe_lock(lock_file).state == LOCK_PROBE_FREE
    successor = InstanceLock(lock_file)
    successor.acquire()
    try:
        assert read_owner_pid(lock_file) == os.getpid()
    finally:
        successor.release()


def test_sigkill_owner_releases_lock_for_successor(tmp_path: Path) -> None:
    """Concurrency DOD: SIGKILL the holder; a successor acquires the same lock."""

    data = _data_dir(tmp_path, "killed")
    lock_file = data / "maintainer.lock"
    code = (
        "import sys, time;"
        f"sys.path.insert(0, {str(REPO_ROOT / 'src')!r});"
        "from ai_maintainer.util.fs import InstanceLock;"
        f"InstanceLock({str(lock_file)!r}).acquire();"
        "time.sleep(60)"
    )
    holder = subprocess.Popen(
        [sys.executable, "-c", code],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            probe = probe_lock(lock_file)
            if probe.state == LOCK_PROBE_HELD:
                break
            time.sleep(0.05)
        assert probe.state == LOCK_PROBE_HELD
        holder.send_signal(signal.SIGKILL)
        holder.wait(timeout=10)
        # Kernel released the flock: probe now says free, no manual cleanup.
        assert probe_lock(lock_file).state == LOCK_PROBE_FREE
        successor = InstanceLock(lock_file)
        successor.acquire()
        try:
            assert read_owner_pid(lock_file) == os.getpid()
        finally:
            successor.release()
    finally:
        if holder.poll() is None:
            holder.kill()
