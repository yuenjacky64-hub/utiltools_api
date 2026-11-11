"""UTILTOOLS-102: the process-lifetime instance lock (REQ-01/REQ-02/REQ-03/REQ-04)."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ai_maintainer.util.fs import (  # noqa: E402
    LOCK_PROBE_FREE,
    LOCK_PROBE_HELD,
    LOCK_PROBE_UNAVAILABLE,
    MaintainerAlreadyRunning,
    InstanceLock,
    probe_lock,
    read_owner_pid,
    pid_is_maintainer,
)
from ai_maintainer.config import MaintainerConfig  # noqa: E402
from ai_maintainer.engine import MaintainerEngine  # noqa: E402
from ai_maintainer.shutdown import EngineState  # noqa: E402
from ai_maintainer.tasks.curriculum import (  # noqa: E402
    Difficulty,
    TaskCurriculum,
    TaskSpec,
    TaskState,
)

REPO_ROOT = Path(__file__).resolve().parents[1]


def _data_dir(tmp_path: Path, name: str) -> Path:
    data = tmp_path / name / "data"
    data.mkdir(parents=True)
    return data


class _FrozenClock:
    """Injectable clock: only advances when the test (or the sleeper) moves it."""

    def __init__(self, now: float = 1_000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> float:
        self.now += seconds
        return self.now


def _long_sec(default: float) -> float:
    """F-03: CI-tunable upper bound for the acceptance tests' wait timeouts.

    ``UTILTOOLS_LOCK_TEST_LONG_SEC`` overrides the generous default so the
    suite can run fast in CI; an unset or invalid value keeps the default,
    so production behaviour is untouched (this is a test seam only).
    """

    raw = os.environ.get("UTILTOOLS_LOCK_TEST_LONG_SEC")
    if raw:
        try:
            return float(raw)
        except ValueError:
            return default
    return default


def _fresh_engine(label: str, state_root: Path) -> tuple[MaintainerEngine, list[float]]:
    """One-task engine on a scratch state root (plan §3 DEV-05 shape).

    The sleeper auto-stops the loop after three injected cycles so
    ``run_forever`` returns without a signal handler; the call list is
    returned so a test can prove how many cycles an engine actually ran.
    """

    state_root.mkdir(parents=True, exist_ok=True)
    clock = _FrozenClock()
    calls: list[float] = []

    def _sleeper(seconds: float) -> None:
        calls.append(seconds)
        clock.advance(seconds)
        if len(calls) >= 3:
            engine.request_stop()

    curriculum = TaskCurriculum(
        specs=[
            TaskSpec(
                name=label,
                difficulty=Difficulty.EASY,
                steps_required=4,
                progress=1,
                last_proposed_cycle=1,
                state=TaskState.RUNNING,
            )
        ],
        config=MaintainerConfig(),
        clock=clock,
    )
    engine = MaintainerEngine(
        curriculum,
        config=MaintainerConfig(),
        clock=clock,
        sleep=_sleeper,
        state_root=state_root,
        shutdown_audit_path=state_root / f"{label}_shutdown.log",
    )
    return engine, calls


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


# ------------------------------------------------------------------
# Plan §8 acceptance tests (DOD-01/02/03/05/07)
# ------------------------------------------------------------------


def test_concurrent_start_lets_exactly_one_daemon_own_the_lock(tmp_path: Path) -> None:
    """DOD-01: two real subprocesses race ``start`` on one MAINTAINER_ROOT.

    Exactly one enters the loop and owns pid/heartbeat/lock; the other exits
    non-zero naming the owner and leaves the winner's marker bytes untouched.
    """

    scratch = tmp_path / "dod01-scratch"
    scratch.mkdir()
    pid_file = scratch / "maintainer.pid"
    heartbeat_file = scratch / "maintainer.heartbeat"
    lock_file = scratch / "maintainer.lock"
    assert not pid_file.exists()  # race window: no pid file yet (DOD-05a precondition)

    env = {
        **os.environ,
        "MAINTAINER_ROOT": str(scratch),
        "MAINTAINER_MAX_SHUTDOWN_WAIT_SEC": "5",
    }
    args = [sys.executable, str(REPO_ROOT / "cli.py"), "start", "--foreground"]
    winner_proc = subprocess.Popen(
        args,
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )
    loser_proc: subprocess.Popen | None = None
    try:
        deadline = time.monotonic() + 15
        owner_pid = None
        while time.monotonic() < deadline:
            probe = probe_lock(lock_file)
            # Wait for a held lock *with* a recorded owner pid: ``acquire``
            # records the pid just after ``flock``, so a HELD probe in that
            # window reports no owner yet.
            if probe.state == LOCK_PROBE_HELD and probe.owner_pid is not None:
                owner_pid = probe.owner_pid
                break
            time.sleep(0.05)
        assert owner_pid == winner_proc.pid
        assert int(pid_file.read_text()) == owner_pid

        loser_proc: subprocess.Popen = subprocess.Popen(
            args,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        loser_proc.wait(timeout=_long_sec(30))
        # Loser exits non-zero and names the live owner (REQ-03).
        loser_stderr = loser_proc.stderr.read() if loser_proc.stderr else ""
        assert loser_proc.returncode != 0
        assert str(owner_pid) in loser_stderr
        # Exactly one loop is live: the winner still runs and owns the lock.
        assert winner_proc.poll() is None
        assert probe_lock(lock_file).owner_pid == owner_pid
        # Loser left the winner's markers untouched: pid and heartbeat still
        # name the winner in the atomic single-line shapes.
        assert pid_file.read_text() == f"{owner_pid}\n"
        assert json.loads(heartbeat_file.read_text())["pid"] == owner_pid
    finally:
        # Cleanup only: no assertions in ``finally`` (NIT), so a failure in
        # the main body is not masked by teardown noise. A graceful stop
        # releases the lock and clears the winner's markers.
        if winner_proc.poll() is None:
            winner_proc.send_signal(signal.SIGTERM)
        winner_proc.wait(timeout=_long_sec(30))
        if loser_proc is not None and loser_proc.poll() is None:
            loser_proc.send_signal(signal.SIGKILL)
            loser_proc.wait(timeout=_long_sec(30))
    # Success-path invariants (moved out of ``finally``; only reached when
    # the body above completed, with the winner stopped gracefully).
    assert winner_proc.returncode == 0
    assert probe_lock(lock_file).state == LOCK_PROBE_FREE


def test_sigkilled_owner_is_replaced_without_manual_cleanup(tmp_path: Path) -> None:
    """DOD-02: SIGKILL the lock holder, then a new engine starts in the same
    data directory; it acquires the released lock, replaces the stale pid and
    begins its loop - no manual cleanup.
    """

    state = tmp_path / "dod02-state"
    state.mkdir()
    lock_file = state / "maintainer.lock"
    pid_file = state / "maintainer.pid"

    code = (
        "import os, sys, time;"
        f"sys.path.insert(0, {str(REPO_ROOT / 'src')!r});"
        "from ai_maintainer.util.fs import InstanceLock;"
        f"lock = InstanceLock({str(lock_file)!r}); lock.acquire();"
        f"open({str(pid_file)!r}, 'w').write(str(os.getpid()) + chr(10));"
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
            if probe_lock(lock_file).state == LOCK_PROBE_HELD:
                break
            time.sleep(0.05)
        assert probe_lock(lock_file).state == LOCK_PROBE_HELD
        holder.send_signal(signal.SIGKILL)
        holder.wait(timeout=10)
        # Kernel released the flock: probe says free; the stale pid is on disk.
        assert probe_lock(lock_file).state == LOCK_PROBE_FREE
        assert int(pid_file.read_text()) == holder.pid

        # A successor engine in the *same* data directory picks up where the
        # SIGKILLed owner left off - no manual cleanup.
        engine, _calls = _fresh_engine("dod02", state)
        captured: dict[str, str] = {}
        base_sleep = engine.sleep

        def _recording_sleeper(seconds: float) -> None:
            if not captured:
                captured["pid"] = engine.pid_file.read_text()
            base_sleep(seconds)

        engine.sleep = _recording_sleeper
        report = engine.run_forever(0.01, install_signals=False)
        assert report.state is EngineState.STOPPED
        # The stale pid was replaced by the successor's own pid (REQ-04).
        assert captured["pid"].strip() == str(os.getpid())
        # The lock was released on the successor's exit and the file survives.
        assert probe_lock(lock_file).state == LOCK_PROBE_FREE
        assert lock_file.exists()
    finally:
        if holder.poll() is None:
            holder.kill()


def test_exiting_owner_does_not_unlink_successor_markers(tmp_path: Path) -> None:
    """DOD-03: the exiting owner's marker cleanup is ownership-gated (REQ-05).

    When the pid file no longer names this process - or the lock is no longer
    held - the teardown path leaves the files untouched, so a successor's pid
    and heartbeat markers survive the previous owner's exit.
    """

    state = tmp_path / "dod03-state"
    state.mkdir()
    engine, _calls = _fresh_engine("dod03", state)
    pid_file = engine.pid_file
    heartbeat_file = engine.heartbeat_file
    assert pid_file is not None and heartbeat_file is not None

    engine._acquire_instance_lock()
    # A recycled, live but unrelated pid stands in for the successor.
    unrelated = subprocess.Popen(
        [sys.executable, "-c", "import time; time.sleep(60)"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        # (a) Owner still holds the lock but the markers no longer name it:
        # the cleanup gate must skip the unlink (REQ-05).
        pid_file.write_text(f"{unrelated.pid}\n", encoding="utf-8")
        heartbeat_file.write_text(
            json.dumps({"pid": unrelated.pid, "cycle": 1, "at": 0.0}),
            encoding="utf-8",
        )
        assert engine._clear_runtime_markers() is True
        assert pid_file.exists()
        assert int(pid_file.read_text()) == unrelated.pid
        assert heartbeat_file.exists()

        # (b) Losing the lock means even the owner's *own* markers survive its
        # cleanup path - a daemon that no longer owns the data directory must
        # not touch it.
        engine._release_instance_lock()
        pid_file.write_text(f"{os.getpid()}\n", encoding="utf-8")
        heartbeat_file.write_text(
            json.dumps({"pid": os.getpid(), "cycle": 1, "at": 0.0}),
            encoding="utf-8",
        )
        assert engine._clear_runtime_markers() is True
        assert pid_file.exists()
        assert heartbeat_file.exists()
    finally:
        unrelated.kill()
        unrelated.wait()

    # (c) The normal case: own pid + lock held -> the owner does clear its
    # own markers.
    lock = InstanceLock(engine.lock_file)
    lock.acquire()
    pid_file.write_text(f"{os.getpid()}\n", encoding="utf-8")
    heartbeat_file.write_text(
        json.dumps({"pid": os.getpid(), "cycle": 1, "at": 0.0}),
        encoding="utf-8",
    )
    engine._clear_runtime_markers()
    lock.release()
    assert not pid_file.exists()
    assert not heartbeat_file.exists()


def test_lock_is_released_when_a_cycle_raises(tmp_path: Path) -> None:
    """DOD-05: an exception during a cycle cannot strand the lock (REQ-02)."""

    state = tmp_path / "dod05-state"
    state.mkdir()
    engine, _calls = _fresh_engine("dod05", state)

    def _boom() -> None:
        raise RuntimeError("cycle boom")

    engine.run_cycle = _boom
    try:
        engine.run_forever(0.01, install_signals=False)
    except RuntimeError:
        # The wind-down may absorb the cycle error; either way the lock must be
        # released on this exit path (REQ-02 / DOD-05).
        pass
    assert engine.lock_file is not None
    assert probe_lock(engine.lock_file).state == LOCK_PROBE_FREE
    # Re-acquirable with no manual cleanup, and the file itself was kept.
    again = InstanceLock(engine.lock_file)
    again.acquire()
    try:
        assert read_owner_pid(engine.lock_file) == os.getpid()
    finally:
        again.release()
    assert engine.lock_file.exists()


def test_race_window_without_pid_file_is_closed(tmp_path: Path) -> None:
    """DOD-05a: with no pid file yet, two concurrent loops close the race.

    Exactly one acquires the lock and writes the markers; the other raises
    :class:`MaintainerAlreadyRunning` before it touches pid, heartbeat or the
    stop flag, and the winner's pid file is what exists afterwards.
    """

    state = tmp_path / "dod05a-state"
    state.mkdir()
    engine_a, calls_a = _fresh_engine("raceA", state)
    engine_b, calls_b = _fresh_engine("raceB", state)
    assert engine_a.lock_file == engine_b.lock_file
    assert not engine_a.pid_file.exists()  # race window: no pid file yet
    calls = {"a": calls_a, "b": calls_b}

    gate = threading.Barrier(2)
    results: dict[str, object] = {}

    def _runner(name: str, engine: MaintainerEngine) -> None:
        gate.wait()
        try:
            results[name] = engine.run_forever(0.01, install_signals=False)
        except MaintainerAlreadyRunning as exc:
            results[name] = exc

    thread_a = threading.Thread(target=_runner, args=("a", engine_a))
    thread_b = threading.Thread(target=_runner, args=("b", engine_b))
    thread_a.start()
    thread_b.start()
    thread_a.join(timeout=_long_sec(30))
    thread_b.join(timeout=_long_sec(30))

    outcome_a, outcome_b = results.get("a"), results.get("b")
    assert isinstance(outcome_a, MaintainerAlreadyRunning) or isinstance(
        outcome_b, MaintainerAlreadyRunning
    ), "at least one loop must lose the lock race"
    assert not (
        isinstance(outcome_a, MaintainerAlreadyRunning)
        and isinstance(outcome_b, MaintainerAlreadyRunning)
    ), "exactly one loop may enter"
    loser = engine_a if isinstance(outcome_a, MaintainerAlreadyRunning) else engine_b
    winner_name = "b" if loser is engine_a else "a"
    loser_name = "a" if loser is engine_a else "b"
    # The winner entered the loop: injected cycles ran to the auto-stop.
    assert not isinstance(results[winner_name], MaintainerAlreadyRunning)
    assert len(calls[winner_name]) >= 1
    # The loser raised at acquisition - before writing any marker, before any
    # cycle - and named the owner.
    assert len(calls[loser_name]) == 0
    assert isinstance(results[loser_name], MaintainerAlreadyRunning)
    assert results[loser_name].owner_pid == os.getpid()
    # After both exits: the lock is free and the marker files are gone -
    # each owner cleared only its own files (REQ-05).
    assert probe_lock(loser.lock_file).state == LOCK_PROBE_FREE
    assert not loser.pid_file.exists()


def test_probe_is_total_over_garbage_payloads(tmp_path: Path) -> None:
    """DOD-07: probes are total over malformed lock payloads (INV-LOCK-4/8).

    Empty files, non-integer text, out-of-pid-range integers and absent files
    never raise; a recycled, live but unrelated pid must not be reported as
    the maintainer (REQ-04).
    """

    data = _data_dir(tmp_path, "garbage")
    lock_file = data / "maintainer.lock"

    # Absent file: free, and probing created nothing (REQ-06 hygiene).
    probe = probe_lock(lock_file)
    assert probe.state == LOCK_PROBE_FREE
    assert probe.owner_pid is None
    assert not lock_file.exists()

    lock = InstanceLock(lock_file)
    lock.acquire()
    try:
        for payload in (
            "",
            "12ab\n",
            "0",
            "-5",
            str(2**63 + 1),  # overflow the platform pid space
        ):
            lock_file.write_text(payload, encoding="utf-8")
            probe = probe_lock(lock_file)  # must not raise for any payload
            assert probe.state == LOCK_PROBE_HELD
            assert probe.owner_pid in (None, os.getpid(), 2**63 + 1)
        # Empty payload: held, but no owner can be reported - total, not crash.
        lock_file.write_text("", encoding="utf-8")
        assert probe_lock(lock_file).state == LOCK_PROBE_HELD
        assert probe_lock(lock_file).owner_pid is None
    finally:
        lock.release()

    # read_owner_pid is total too: garbage stays ``None``, never an exception.
    for payload in ("12ab", "0", "-5", ""):
        lock_file.write_text(payload, encoding="utf-8")
        assert read_owner_pid(lock_file) is None
    lock_file.write_text(str(2**63 + 1), encoding="utf-8")
    assert read_owner_pid(lock_file) == 2**63 + 1
    # A 26-digit value (far past any pid_t range) must round-trip verbatim
    # through ``read_owner_pid``: it validates shape/type, not pid space.
    lock_file.write_text("1" * 26, encoding="utf-8")
    assert read_owner_pid(lock_file) == int("1" * 26)

    # A mode-000 lock file is unreadable: the owner-pid read degrades to
    # None and the probe degrades to UNAVAILABLE (both stay total). Under
    # root, file modes are bypassed, so the check only bites when the suite
    # runs unprivileged.
    if os.geteuid() != 0:
        lock_file.chmod(0o000)
        try:
            assert read_owner_pid(lock_file) is None
            assert probe_lock(lock_file).state == LOCK_PROBE_UNAVAILABLE
        finally:
            lock_file.chmod(0o644)

    # A recycled, live but unrelated pid is not the maintainer (REQ-04).
    unrelated = subprocess.Popen(
        [sys.executable, "-c", "import time; time.sleep(60)"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        assert probe_lock(lock_file).state == LOCK_PROBE_FREE  # nobody holds
        assert pid_is_maintainer(unrelated.pid, lock_path=lock_file) is False
    finally:
        unrelated.kill()
        unrelated.wait()

    # A live, recycled pid that does not own the lock is not the maintainer.
    lock.acquire()
    try:
        assert pid_is_maintainer(os.getpid(), lock_path=lock_file) is True
        probe = probe_lock(lock_file)
        assert probe.state == LOCK_PROBE_HELD and probe.owner_pid == os.getpid()
    finally:
        lock.release()
    assert pid_is_maintainer(os.getpid(), lock_path=lock_file) is False
