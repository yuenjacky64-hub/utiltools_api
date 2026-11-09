"""Execution leases and the stale-cycle reaper (UTILTOOLS-78).

Covers the task brief REQ-01 .. REQ-05: the ``Lease`` record (DOD-01), mutual
exclusion through ``acquire_lease`` / ``release_lease`` (DOD-02 .. DOD-04), the
``ACTIVE -> EXPIRED -> RECLAIMED`` reaper including its no-op cases (DOD-05 ..
DOD-07, DOD-13, DOD-14), the ``run_cycle`` / ``run_forever`` frame that never
lets a body run or an exit path leak (DOD-08 .. DOD-10), and the audit log in
the engine's data directory (DOD-11, DOD-12).

The clock and sleep stay injected, so TTL boundaries are asserted without a
real sleep; the only real-time waits are the ``threading.Event`` rendezvous
where a blocking acquire is the behaviour under test.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.engine import (
    DEFAULT_LEASE_TTL_SEC,
    LEASE_ACQUIRED,
    LEASE_AUDIT_NAME,
    LEASE_EXPIRED,
    LEASE_HISTORY_MAXLEN,
    LEASE_RECLAIMED,
    LEASE_REFUSED,
    LEASE_RELEASED,
    LEASE_STATUS_ACTIVE,
    LEASE_STATUS_EXPIRED,
    LEASE_STATUS_RECLAIMED,
    Lease,
    LeaseHeldError,
    LeaseNotHeldError,
    LeaseStatus,
    LeaseTransitionError,
    MaintainerEngine,
)
from ai_maintainer.memory import MemoryStore
from ai_maintainer.tasks.curriculum import (
    Difficulty,
    TaskCurriculum,
    TaskSpec,
    TaskState,
)


class FrozenClock:
    """Clock that only moves when the test moves it (monotonic stand-in)."""

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


def _spec(name: str = "alpha", *, steps_required: int = 4) -> TaskSpec:
    return TaskSpec(
        name=name,
        difficulty=Difficulty.EASY,
        steps_required=steps_required,
        progress=1,
        last_proposed_cycle=100,
        state=TaskState.RUNNING,
    )


def _engine(
    tmp_path: Path,
    *,
    specs: list[TaskSpec] | None = None,
    clock: FrozenClock | None = None,
    sleeper: FakeSleeper | None = None,
    ttl: float | None = None,
    memory: MemoryStore | None = None,
    framework_idle: object = None,
    state_root: str | Path | None = "default",
) -> MaintainerEngine:
    clock = clock if clock is not None else FrozenClock()
    sleeper = sleeper if sleeper is not None else FakeSleeper(clock)
    cfg = MaintainerConfig()
    root: Path | None
    if state_root == "default":
        root = tmp_path / "state"
    elif state_root is None:
        root = None
    else:
        root = Path(state_root)
    curriculum = TaskCurriculum(specs=specs if specs is not None else [_spec()], config=cfg, clock=clock)
    kwargs: dict[str, object] = {
        "config": cfg,
        "clock": clock,
        "sleep": sleeper,
        "state_root": root,
        "shutdown_audit_path": tmp_path / "data" / "maintainer_shutdown.log",
        "memory": memory,
    }
    if ttl is not None:
        kwargs["lease_ttl_sec"] = ttl
    if framework_idle is not None:
        kwargs["framework_idle"] = framework_idle
    return MaintainerEngine(curriculum, **kwargs)


def _audit_lines(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    raw = path.read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in raw if line.strip()]


def _lease_events(engine: MaintainerEngine) -> list[dict]:
    assert engine.reporter is not None and engine.reporter.path is not None
    return _audit_lines(engine.reporter.path)


def _events_named(lines: list[dict], name: str) -> list[dict]:
    return [line for line in lines if line.get("event") == name]


# --------------------------------------------------------------- REQ-01 Lease


def test_lease_dataclass_fields() -> None:
    """DOD-01/REQ-01: exactly the five named fields, a three-state enum, and the
    tokens/round-trip a reader (audit line, snapshot) depends on."""

    lease = Lease(lease_id="l1", cycle_id="cycle-1", acquired_at=10.0, expires_at=70.0)
    assert (lease.lease_id, lease.cycle_id) == ("l1", "cycle-1")
    assert (lease.acquired_at, lease.expires_at) == (10.0, 70.0)
    assert lease.status is LeaseStatus.ACTIVE
    assert [status.value for status in LeaseStatus] == [
        LEASE_STATUS_ACTIVE,
        LEASE_STATUS_EXPIRED,
        LEASE_STATUS_RECLAIMED,
    ]
    assert lease.to_dict() == {
        "lease_id": "l1",
        "cycle_id": "cycle-1",
        "acquired_at": 10.0,
        "expires_at": 70.0,
        "status": LEASE_STATUS_ACTIVE,
    }
    # Staleness is TTL-and-status, never TTL alone (REQ-03).
    assert lease.is_expired(70.0) is False
    assert lease.is_expired(70.5) is True
    reclaimed = Lease(
        lease_id="l2",
        cycle_id="cycle-2",
        acquired_at=0.0,
        expires_at=1.0,
        status=LeaseStatus.RECLAIMED,
    )
    assert reclaimed.is_expired(99.0) is False


# ------------------------------------------------------ REQ-02 acquire/release


def test_acquire_lease_blocks_while_held(tmp_path: Path) -> None:
    """DOD-02/REQ-02: a second cycle parks on the ``threading.Lock`` until the
    holder releases — it cannot interleave into the body."""

    clock = FrozenClock()
    engine = _engine(tmp_path, clock=clock, ttl=60.0)
    assert isinstance(engine._lease_lock, type(threading.Lock()))

    holder_ready = threading.Event()
    may_release = threading.Event()
    waiter_granted = threading.Event()
    granted: dict[str, str] = {}

    def holder() -> None:
        lease = engine.acquire_lease("cycle-1")
        granted["holder"] = lease.lease_id
        holder_ready.set()
        may_release.wait(5.0)
        engine.release_lease(lease.lease_id)

    def waiter() -> None:
        lease = engine.acquire_lease("cycle-2")
        granted["waiter"] = lease.lease_id
        waiter_granted.set()
        # Released by its own owner: a lease is not transferable between threads.
        engine.release_lease(lease.lease_id)

    holder_thread = threading.Thread(target=holder)
    waiter_thread = threading.Thread(target=waiter)
    holder_thread.start()
    assert holder_ready.wait(5.0)
    waiter_thread.start()

    # The waiter is parked: no grant, and a non-blocking acquire refuses.
    assert waiter_granted.wait(0.2) is False
    assert "waiter" not in granted
    with pytest.raises(LeaseHeldError):
        engine.acquire_lease("cycle-3", block=False)

    may_release.set()
    assert waiter_granted.wait(5.0)
    holder_thread.join(5.0)
    waiter_thread.join(5.0)
    assert granted["holder"] != granted["waiter"]
    assert engine._active_lease is None


def test_release_lease_frees_slot(tmp_path: Path) -> None:
    """DOD-03/REQ-02: after a release the slot is immediately available again to
    a *different* cycle and yields a fresh lease id."""

    engine = _engine(tmp_path)
    first = engine.acquire_lease("cycle-1")
    assert first.status is LeaseStatus.ACTIVE
    released = engine.release_lease(first.lease_id)
    assert released.status is LeaseStatus.RECLAIMED
    assert engine._active_lease is None

    second = engine.acquire_lease("cycle-2")
    assert second.lease_id != first.lease_id
    assert second.cycle_id == "cycle-2"
    assert second.status is LeaseStatus.ACTIVE
    engine.release_lease(second.lease_id)


def test_double_release_is_refused(tmp_path: Path) -> None:
    """DOD-04/REQ-02: releasing a released lease raises, leaves the terminal
    status untouched, and audits exactly one release plus one refusal."""

    engine = _engine(tmp_path)
    lease = engine.acquire_lease("cycle-1")
    engine.release_lease(lease.lease_id)

    with pytest.raises(LeaseTransitionError):
        engine.release_lease(lease.lease_id)

    assert engine._leases[lease.lease_id].status is LeaseStatus.RECLAIMED
    lines = _lease_events(engine)
    assert len(_events_named(lines, LEASE_RELEASED)) == 1
    refusals = _events_named(lines, LEASE_REFUSED)
    assert len(refusals) == 1
    assert refusals[0]["reason"] == "already_released"
    # A second terminal event was never emitted for the same lease.
    assert _events_named(lines, LEASE_RECLAIMED) == []

    # An unknown id is refused on the same path without inventing state.
    with pytest.raises(LeaseTransitionError):
        engine.release_lease("no-such-lease")
    assert _events_named(_lease_events(engine), LEASE_REFUSED)[-1]["reason"] == "unknown_lease"


# ------------------------------------------------------------- REQ-03 reaper


def test_reap_stale_leases_transitions_expired(tmp_path: Path) -> None:
    """DOD-05/REQ-03: an expired ACTIVE lease goes ACTIVE -> EXPIRED ->
    RECLAIMED, in that audit order, and lands in ``ReapReport.reaped``."""

    clock = FrozenClock()
    engine = _engine(tmp_path, clock=clock, ttl=60.0)
    lease = engine.acquire_lease("cycle-1")
    assert lease.expires_at == pytest.approx(clock.now + 60.0)

    report = engine._reap_stale_leases(now=lease.expires_at + 1)
    assert report.reaped == [lease.lease_id]
    assert report.skipped == 0
    assert engine._leases[lease.lease_id].status is LeaseStatus.RECLAIMED
    assert engine._active_lease is None

    lines = _lease_events(engine)
    named = [(line["event"], line.get("reason")) for line in lines]
    assert (LEASE_EXPIRED, "ttl_elapsed") in named
    assert (LEASE_RECLAIMED, "stale") in named
    expired_at = named.index((LEASE_EXPIRED, "ttl_elapsed"))
    reclaimed_at = named.index((LEASE_RECLAIMED, "stale"))
    assert expired_at < reclaimed_at

    # The slot is free again after the reclaim.
    again = engine.acquire_lease("cycle-2")
    engine.release_lease(again.lease_id)


def test_reap_already_completed_cycle_is_noop(tmp_path: Path) -> None:
    """DOD-06/REQ-03: reaping a cycle that already finished is a no-op — no
    second terminal event, status unchanged, counted as skipped."""

    clock = FrozenClock()
    engine = _engine(tmp_path, clock=clock, ttl=60.0)
    lease = engine.acquire_lease("cycle-1")
    engine.release_lease(lease.lease_id)
    before = _lease_events(engine)
    assert len(before) == 2

    report = engine._reap_stale_leases(now=lease.expires_at + 1)
    assert report.reaped == []
    assert report.skipped >= 1
    assert engine._leases[lease.lease_id].status is LeaseStatus.RECLAIMED
    assert len(_lease_events(engine)) == len(before)


def test_reap_ignores_live_lease(tmp_path: Path) -> None:
    """DOD-07/REQ-03: before ``expires_at`` the lease stays ACTIVE and no
    expiration line is written."""

    clock = FrozenClock()
    engine = _engine(tmp_path, clock=clock, ttl=60.0)
    lease = engine.acquire_lease("cycle-1")

    report = engine._reap_stale_leases(now=lease.expires_at - 0.001)
    assert report.reaped == []
    assert report.skipped >= 1
    assert engine._leases[lease.lease_id].status is LeaseStatus.ACTIVE
    assert _events_named(_lease_events(engine), LEASE_EXPIRED) == []
    # The holder can still release normally afterwards.
    engine.release_lease(lease.lease_id)


def test_reap_prunes_memory(tmp_path: Path) -> None:
    """DOD-13/REQ-03: a reclaiming pass runs the existing memory maintenance
    path exactly once, and never when no store is installed."""

    clock = FrozenClock()
    store = MemoryStore(tmp_path / "memory.json")
    engine = _engine(tmp_path, clock=clock, ttl=60.0, memory=store)
    calls: list[object] = []
    original = engine._prune_memory

    def counting(memory: object) -> tuple[dict[str, int], str | None]:
        calls.append(memory)
        return original(memory)

    engine._prune_memory = counting  # type: ignore[method-assign]
    lease = engine.acquire_lease("cycle-1")
    engine._reap_stale_leases(now=lease.expires_at + 1)
    assert calls == [store]

    # Nothing reaped -> no maintenance call.
    calls.clear()
    second = engine.acquire_lease("cycle-2")
    engine._reap_stale_leases(now=second.acquired_at)
    assert calls == []
    engine.release_lease(second.lease_id)

    # No store installed -> no maintenance call even on a reclaiming pass.
    bare = _engine(tmp_path / "bare", clock=FrozenClock(), ttl=60.0)
    bare_lease = bare.acquire_lease("cycle-1")
    report = bare._reap_stale_leases(now=bare_lease.expires_at + 1)
    assert report.reaped == [bare_lease.lease_id]


def test_lease_history_is_bounded(tmp_path: Path) -> None:
    """DOD-14/REQ-03: the registry is bounded by ``LEASE_HISTORY_MAXLEN`` and the
    lease in flight is never a prune candidate."""

    engine = _engine(tmp_path)
    for _ in range(LEASE_HISTORY_MAXLEN + 20):
        lease = engine.acquire_lease("cycle-x")
        engine.release_lease(lease.lease_id)

    assert len(engine._leases) <= LEASE_HISTORY_MAXLEN
    held = engine.acquire_lease("cycle-live")
    assert engine._leases[held.lease_id].status is LeaseStatus.ACTIVE
    assert len(engine._leases) <= LEASE_HISTORY_MAXLEN
    engine.release_lease(held.lease_id)


# --------------------------------------------------------- REQ-04 integration


def test_run_cycle_acquires_and_releases_lease(tmp_path: Path) -> None:
    """DOD-08/REQ-04: the body always runs under a lease whose ``cycle_id``
    matches the ``CycleResult``, and success leaves no ACTIVE lease behind."""

    engine = _engine(tmp_path)
    seen: list[Lease] = []
    original = engine._run_cycle_body

    def spying() -> object:
        seen.append(engine._require_active_lease())
        return original()

    engine._run_cycle_body = spying  # type: ignore[method-assign]
    engine.run_cycle()
    assert len(seen) == 1
    assert seen[0].cycle_id.startswith("cycle-")
    assert seen[0].status is LeaseStatus.ACTIVE
    assert engine._active_lease is None
    assert [l["status"] for l in engine.lease_snapshot()] == [LEASE_STATUS_RECLAIMED]

    # A body exception still releases the slot.
    def exploding() -> object:
        raise RuntimeError("boom")

    engine._run_cycle_body = exploding  # type: ignore[method-assign]
    with pytest.raises(RuntimeError):
        engine.run_cycle()
    assert engine._active_lease is None
    assert engine._cycle_done.is_set()
    latest = engine.lease_snapshot()[-1]
    assert latest["status"] == LEASE_STATUS_RECLAIMED


def test_cycle_without_lease_is_rejected(tmp_path: Path) -> None:
    """DOD-09/REQ-04: entering the body without a lease raises
    ``LeaseNotHeldError``, and a framework that is not idle runs nothing."""

    engine = _engine(tmp_path)
    with pytest.raises(LeaseNotHeldError):
        engine._run_cycle_body()
    assert _lease_events(engine) == []
    assert engine._cycles == 0

    busy = _engine(tmp_path / "busy", framework_idle=lambda: False)
    result = busy.run_cycle()
    assert result.steps == 0
    assert busy._active_lease is None
    assert _lease_events(busy) == []


def test_run_forever_reaps_between_cycles(tmp_path: Path) -> None:
    """DOD-10/REQ-04: ``run_forever()`` reaps after the cycle and before the
    pause; the parked expired lease is reclaimed and the loop still stops."""

    clock = FrozenClock()
    sleeper = FakeSleeper(clock)
    engine = _engine(tmp_path, clock=clock, sleeper=sleeper, ttl=60.0)

    # A stalled cycle left an ACTIVE lease whose TTL is long gone. The record is
    # seeded directly (a dead holder no longer owns the slot, so the loop's own
    # acquire is not parked behind it); the expired lease is still the reaper's
    # job to clear.
    stalled = Lease(
        lease_id="stalled-lease",
        cycle_id="cycle-stalled",
        acquired_at=clock.now,
        expires_at=clock.now + 1.0,
    )
    engine._leases[stalled.lease_id] = stalled
    clock.advance(engine._lease_ttl_sec + 5)
    seen: list[list[str]] = []
    original = engine._reap_stale_leases

    def spying(now: float | None = None) -> object:
        report = original(now=now)
        seen.append(list(report.reaped))
        engine.request_stop("test")
        return report

    engine._reap_stale_leases = spying  # type: ignore[method-assign]
    engine.run_forever(interval_sec=1.0)

    assert seen == [[stalled.lease_id]]
    assert engine._leases[stalled.lease_id].status is LeaseStatus.RECLAIMED
    assert sleeper.calls == []


# ------------------------------------------------------------- REQ-05 audit


def test_audit_log_records_lease_events(tmp_path: Path) -> None:
    """DOD-11/REQ-05: acquisition, release, expiration and reclamation are all
    in ``<state_root>/maintainer_leases.log`` with the lease/cycle identity."""

    clock = FrozenClock()
    engine = _engine(tmp_path, clock=clock, ttl=60.0)
    assert engine.reporter is not None
    path = engine.reporter.path
    assert path == tmp_path / "state" / LEASE_AUDIT_NAME
    assert engine._lease_ttl_sec == pytest.approx(60.0)
    assert DEFAULT_LEASE_TTL_SEC == 60.0

    released = engine.acquire_lease("cycle-1")
    engine.release_lease(released.lease_id)
    stalled = engine.acquire_lease("cycle-2")
    engine._reap_stale_leases(now=stalled.expires_at + 1)

    lines = _lease_events(engine)
    names = [line["event"] for line in lines]
    for name in (LEASE_ACQUIRED, LEASE_RELEASED, LEASE_EXPIRED, LEASE_RECLAIMED):
        assert name in names
    assert names.count(LEASE_ACQUIRED) == 2
    for line in lines:
        assert line["lease_id"]
        assert line["cycle_id"].startswith("cycle-")
        assert line["status"] in {
            LEASE_STATUS_ACTIVE,
            LEASE_STATUS_EXPIRED,
            LEASE_STATUS_RECLAIMED,
        }
        assert "recorded_at" in line or "ts" in line

    # The file is a runtime artifact and is named in .gitignore.
    ignored = Path(__file__).resolve().parents[1] / ".gitignore"
    assert LEASE_AUDIT_NAME in ignored.read_text(encoding="utf-8")


def test_snapshot_exposes_lease_state(tmp_path: Path) -> None:
    """DOD-12/REQ-02: ``snapshot()["leases"]`` mirrors lease state and stays a
    read-only copy — mutating it cannot corrupt the registry."""

    engine = _engine(tmp_path)
    assert engine.snapshot()["leases"] == []

    lease = engine.acquire_lease("cycle-1")
    view = engine.snapshot()["leases"]
    assert [entry["lease_id"] for entry in view] == [lease.lease_id]
    assert view[0]["status"] == LEASE_STATUS_ACTIVE
    view[0]["status"] = LEASE_STATUS_RECLAIMED
    view.clear()

    assert engine.snapshot()["leases"][0]["status"] == LEASE_STATUS_ACTIVE
    assert engine.lease_snapshot()[0]["cycle_id"] == "cycle-1"
    engine.release_lease(lease.lease_id)
