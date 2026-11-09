"""Priority-ordered maintainer cycle with preemption and aging.

One ``threading.RLock`` guards every read and write of the monitored task, the
task priority bookkeeping and the event deque. Monitor step bodies run outside
that lock and their result is committed only after re-validating that the
stepped task is still the monitored one, so a concurrent preemption can never
double-count progress.

The same lock also guards the per-fingerprint circuit registry: every blocked
cycle is tallied against its *error fingerprint* (the exception type, never the
message), a fingerprint that reaches ``fingerprint_quarantine_threshold``
consecutive blocks is quarantined for ``fingerprint_quarantine_sec``, and a
background sweep pass probes the fingerprint after the window elapses. Probe
bodies run outside the lock, exactly like the monitor step body.
"""

from __future__ import annotations

import json
import logging
import math
import os
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass, field, replace
from enum import Enum
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable

if TYPE_CHECKING:  # pragma: no cover - typing only, never imported at runtime
    from ai_maintainer.memory.store import MemoryStore

from ai_maintainer.concurrency import (
    CycleAdmissionGate,
    CycleSnapshot,
    current_heartbeat,
    read_heartbeat,
    write_heartbeat,
)
from ai_maintainer.config import MaintainerConfig
from ai_maintainer.shutdown import (
    CLEANUP_CLEAR_RUNTIME_MARKERS,
    CLEANUP_PERSIST_TASK_STATE,
    CLEANUP_PRUNE_MEMORY,
    CLEANUP_PRUNE_STALE_TASK_SLOTS,
    CLEANUP_RELEASE_FILE_LOCKS,
    CLEANUP_STOP_SCHEDULER,
    CLEANUP_WRITE_SHUTDOWN_AUDIT,
    PERSIST_SKIPPED_NO_STATE_ROOT,
    SHUTDOWN_POLL_INTERVAL_SEC,
    SHUTDOWN_STATE_SCHEMA,
    SHUTDOWN_STATE_TMP,
    EngineState,
    ShutdownReport,
    default_audit_path,
    write_shutdown_audit,
    write_shutdown_state,
)
from ai_maintainer.tasks.curriculum import (
    NON_TERMINAL,
    STALE_ELIGIBLE,
    Difficulty,
    TaskCurriculum,
    TaskSpec,
    TaskState,
    effective_priority,
    progress_age_sec,
    stamp_pending_since,
    stamp_progress,
)

__all__ = [
    "CancelReport",
    "CycleResult",
    "DEFAULT_CYCLE_INTERVAL_SEC",
    "DEFAULT_CANCEL_REASON",
    "DEFAULT_LEASE_TTL_SEC",
    "DEFAULT_STOP_REASON",
    "FingerprintBucket",
    "FingerprintCircuitState",
    "LEASE_ACQUIRED",
    "LEASE_AUDIT_NAME",
    "LEASE_EXPIRED",
    "LEASE_HISTORY_MAXLEN",
    "LEASE_RECLAIMED",
    "LEASE_REFUSED",
    "LEASE_RELEASED",
    "LEASE_STATUS_ACTIVE",
    "LEASE_STATUS_EXPIRED",
    "LEASE_STATUS_RECLAIMED",
    "Lease",
    "LeaseAuditReporter",
    "LeaseError",
    "LeaseHeldError",
    "LeaseNotHeldError",
    "LeaseStatus",
    "LeaseTimeoutError",
    "LeaseTransitionError",
    "MaintainerEngine",
    "ReapReport",
    "RUNTIME_HEARTBEAT_FILE",
    "RUNTIME_MARKER_TMP_SUFFIX",
    "RUNTIME_PID_FILE",
    "error_fingerprint",
]

EVENT_LOG_MAXLEN = 100
DEFAULT_CYCLE_INTERVAL_SEC = 1.0
DEFAULT_STOP_REASON = "stop_requested"
DEFAULT_CANCEL_REASON = "cancel_requested"

# Execution leases (REQ-01 .. REQ-05): one ``Lease`` per cycle execution, both
# the audit vocabulary and the history bound live here so every call site
# (engine, tests, operator tooling) agrees on the exact tokens.
LEASE_AUDIT_NAME = "maintainer_leases.log"  # runtime artifact, git-ignored
DEFAULT_LEASE_TTL_SEC = 60.0
LEASE_HISTORY_MAXLEN = 100  # memory-growth bound, INV-LEASE-6
LEASE_STATUS_ACTIVE = "ACTIVE"
LEASE_STATUS_EXPIRED = "EXPIRED"
LEASE_STATUS_RECLAIMED = "RECLAIMED"
LEASE_ACQUIRED = "LEASE_ACQUIRED"
LEASE_RELEASED = "LEASE_RELEASED"
LEASE_EXPIRED = "LEASE_EXPIRED"
LEASE_RECLAIMED = "LEASE_RECLAIMED"
LEASE_REFUSED = "LEASE_REFUSED"

# Runtime markers: the two files an operator (and ``cli.py stop``) uses to find
# and stop a live engine. Both live directly under ``state_root`` and both are
# removed by ``shutdown()`` so a stopped engine leaves no stale artifact.
RUNTIME_PID_FILE = "maintainer.pid"
RUNTIME_HEARTBEAT_FILE = "maintainer.heartbeat"
RUNTIME_MARKER_TMP_SUFFIX = ".tmp"

# Wall-clock seam for the audit record (events keep using the monotonic clock).
_wall_clock: Callable[[], float] = time.time

# Marker files (plus their ``.tmp`` siblings) removed when the engine stops.
_RUNTIME_MARKER_NAMES = (
    RUNTIME_PID_FILE,
    RUNTIME_PID_FILE + RUNTIME_MARKER_TMP_SUFFIX,
    RUNTIME_HEARTBEAT_FILE,
    RUNTIME_HEARTBEAT_FILE + RUNTIME_MARKER_TMP_SUFFIX,
)

LOG = logging.getLogger(__name__)


def _release_quietly(lock: Any) -> bool:
    """Best-effort ``lock.release()``; ``True`` on success (incl. "not held")."""

    try:
        lock.release()
    except Exception:
        return False
    return True


def _record_delta(
    before: Mapping[str, Any], after: Mapping[str, Any]
) -> int:
    """How many records ``before`` carries that ``after`` no longer has.

    Used only for the ``SNAPSHOT_RESTORED`` audit count (REQ-02): it compares
    record by record so a restore that happens to keep a peer's identical
    record is not reported as a removal. Both mappings are the three-collection
    shape ``MemoryStore.snapshot_records()`` returns.
    """

    removed = 0
    for name, records in before.items():
        kept = list(after.get(name) or [])
        for record in records or []:
            for index, candidate in enumerate(kept):
                if candidate == record:
                    kept.pop(index)
                    break
            else:
                removed += 1
    return removed


def _difficulty_name(value: int) -> str:
    """Render a stored admission weight as its ``Difficulty`` name.

    ``_CycleContext.difficulty`` keeps the plain int so the registry stays
    comparable/sortable; ``snapshot()`` reports the enum name (``COMPLEX``,
    ``HARD``, ...) because that is what an operator and the plan's
    ``active_cycles`` rows speak (REQ-03).
    """

    try:
        return Difficulty.parse(value).name
    except (TypeError, ValueError):  # pragma: no cover - defensive
        return str(value)

def _atomic_marker_write(path: Path, text: str) -> None:
    """Write ``text`` to ``path`` through a ``.tmp`` sibling (INV-MARK-2).

    Uses ``O_NOFOLLOW`` so a planted symlink is refused instead of followed,
    then ``os.replace`` for an atomic swap. Raises ``OSError`` on any failure;
    callers treat markers as advisory and swallow it.
    """

    if path.is_symlink():
        raise OSError(f"refusing to write runtime marker through symlink: {path}")
    tmp = path.with_name(path.name + RUNTIME_MARKER_TMP_SUFFIX)
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    flags |= getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(tmp, flags, 0o644)
    try:
        os.write(fd, text.encode("utf-8"))
    finally:
        os.close(fd)
    os.replace(tmp, path)


class FingerprintCircuitState(Enum):
    """Circuit state of one error fingerprint."""

    CLOSED = "CLOSED"  # normal operation, no quarantine
    OPEN = "OPEN"  # quarantined until ``quarantine_until``
    HALF_OPEN = "HALF_OPEN"  # quarantine expired, a single probe is in flight


def error_fingerprint(exc: BaseException) -> str:
    """Return the error-type key of ``exc`` (never ``str(exc)``)."""

    error_type = type(exc)
    return f"{error_type.__module__}.{error_type.__qualname__}"


@dataclass
class FingerprintBucket:
    """Consecutive-blocked counter and quarantine window of one fingerprint."""

    fingerprint: str
    consecutive_blocked: int = 0
    quarantine_until: float | None = None
    circuit_state: FingerprintCircuitState = FingerprintCircuitState.CLOSED
    last_cycle: int = 0
    last_seen: float = 0.0
    probe_epoch: int = 0

    def is_quarantined(self, now: float) -> bool:
        """Single coherence rule shared by the skip rule and the endpoint."""

        return (
            self.circuit_state is not FingerprintCircuitState.CLOSED
            and self.quarantine_until is not None
            and now < self.quarantine_until
        )

    def remaining_sec(self, now: float) -> float:
        """Remaining quarantine time, derived (never a mutable countdown)."""

        if self.quarantine_until is None:
            return 0.0
        return max(0.0, self.quarantine_until - now)

    def to_dict(self, *, now: float) -> dict[str, Any]:
        return {
            "fingerprint": self.fingerprint,
            "circuit_state": self.circuit_state.value,
            "consecutive_blocked": self.consecutive_blocked,
            "quarantine_active": self.is_quarantined(now),
            "quarantine_until": self.quarantine_until,
            "remaining_sec": self.remaining_sec(now),
            "last_cycle": self.last_cycle,
        }


@dataclass
class CycleResult:
    """Outcome of a single :meth:`MaintainerEngine.run_cycle` call."""

    cycle: int
    monitored: str | None
    priority: str | None
    preempted: str | None
    steps: int
    # UTILTOOLS-77: trailing defaulted fields, so the historical positional
    # construction keeps working. ``admitted`` is ``False`` only when the cycle
    # observed a stop signal while waiting for a concurrency slot;
    # ``memory_cycle`` is the cycle id its records were stamped with (``None``
    # with no store installed).
    admitted: bool = True
    memory_cycle: int | None = None

    def to_dict(self) -> dict:
        return {
            "cycle": self.cycle,
            "monitored": self.monitored,
            "priority": self.priority,
            "preempted": self.preempted,
            "steps": self.steps,
            "admitted": self.admitted,
            "memory_cycle": self.memory_cycle,
        }


@dataclass
class _CycleContext:
    """One admitted cycle's registry entry (UTILTOOLS-77 REQ-05).

    Every field is written under the engine ``_lock``; ``difficulty`` is the
    admission peek that ordered this cycle against its peers and ``task`` is the
    name of the task its slot last adopted (kept after the slot is retired so
    ``snapshot()`` can still attribute the cycle).
    """

    cycle: int
    difficulty: int
    task: str | None = None
    thread: int | None = None
    admitted_at: float = 0.0

@dataclass(frozen=True)
class CancelReport:
    """Outcome of one :meth:`MaintainerEngine.cancel_task` call (REQ-01).

    ``state`` is the terminal state of the cancelled task (always ``CANCELLED``
    when ``cancelled`` is ``True``); with nothing in flight it is ``None`` and
    ``cancelled`` is ``False``. ``timed_out`` reports whether ``waited_sec`` hit
    the caller's bound *before* the running cycle left ``run_cycle()``.
    """

    task: str | None
    state: str | None
    cancelled: bool
    timed_out: bool
    waited_sec: float
    reason: str

    def to_dict(self) -> dict:
        return {
            "task": self.task,
            "state": self.state,
            "cancelled": self.cancelled,
            "timed_out": self.timed_out,
            "waited_sec": self.waited_sec,
            "reason": self.reason,
        }


class LeaseStatus(str, Enum):
    """Lifecycle of one execution lease (REQ-01).

    ``ACTIVE`` -> ``RECLAIMED`` (released by its owner) or ``ACTIVE`` ->
    ``EXPIRED`` -> ``RECLAIMED`` (TTL elapsed, swept by the reaper). Both
    terminal paths end in ``RECLAIMED`` and are distinguished by the audit
    ``reason`` (``released`` vs ``stale``), never by a second status. A ``str``
    mixin keeps the field JSON-serialisable and audit lines carry the exact
    token the brief names.
    """

    ACTIVE = LEASE_STATUS_ACTIVE
    EXPIRED = LEASE_STATUS_EXPIRED
    RECLAIMED = LEASE_STATUS_RECLAIMED


@dataclass(frozen=True)
class Lease:
    """One typed record per cycle execution (REQ-01).

    ``expires_at`` is ``acquired_at + lease_ttl_sec``. A lease is stale once
    ``now > expires_at`` *and* its status is still ``ACTIVE`` (REQ-03). Frozen
    so a reader can never mutate engine state through a snapshot object; every
    transition goes through :func:`dataclasses.replace`.
    """

    lease_id: str
    cycle_id: str
    acquired_at: float
    expires_at: float
    status: LeaseStatus = LeaseStatus.ACTIVE

    def is_expired(self, now: float) -> bool:
        """True when the TTL elapsed and the lease was never released."""

        return self.status is LeaseStatus.ACTIVE and now > self.expires_at

    def to_dict(self) -> dict:
        return {
            "lease_id": self.lease_id,
            "cycle_id": self.cycle_id,
            "acquired_at": self.acquired_at,
            "expires_at": self.expires_at,
            "status": LeaseStatus(self.status).value,
        }


@dataclass(frozen=True)
class ReapReport:
    """Outcome of one :meth:`MaintainerEngine._reap_stale_leases` pass (REQ-03).

    ``reaped`` lists only the ids actually transitioned this pass; ``skipped``
    counts the leases the pass left alone, so a no-op sweep can never be
    mistaken for a reclaim.
    """

    at: float
    reaped: list[str] = field(default_factory=list)
    skipped: int = 0

    def to_dict(self) -> dict:
        return {"at": self.at, "reaped": list(self.reaped), "skipped": self.skipped}


class LeaseError(RuntimeError):
    """Base class for lease state-machine failures."""


class LeaseHeldError(LeaseError):
    """Non-blocking acquire refused: another cycle holds an ACTIVE lease (REQ-02)."""

    def __init__(self, cycle_id: str, held: Lease) -> None:
        self.cycle_id = cycle_id
        self.held = held
        super().__init__(
            f"lease already held: cycle {cycle_id!r} refused, "
            f"{held.lease_id} (cycle {held.cycle_id!r}) is ACTIVE"
        )


class LeaseTimeoutError(LeaseError):
    """A blocking acquire waited past ``timeout`` while a lease stayed ACTIVE."""

    def __init__(self, cycle_id: str, timeout: float | None, held: Lease | None = None) -> None:
        self.cycle_id = cycle_id
        self.timeout = timeout
        self.held = held
        super().__init__(
            f"timed out after {timeout!r}s waiting for the active lease "
            f"to run cycle {cycle_id!r}"
        )


class LeaseNotHeldError(LeaseError):
    """Release from the wrong thread, or a cycle body entered without a lease."""

    def __init__(self, lease_id: str | None, reason: str) -> None:
        self.lease_id = lease_id
        self.reason = reason
        super().__init__(f"lease {lease_id!r} not held: {reason}")


class LeaseTransitionError(LeaseError):
    """Release/reap of a lease in a terminal state (double-release, unknown id)."""

    def __init__(self, lease_id: str, reason: str) -> None:
        self.lease_id = lease_id
        self.reason = reason
        super().__init__(f"lease {lease_id!r} transition refused: {reason}")


class LeaseAuditReporter:
    """``self.reporter`` for lease events (REQ-05).

    Appends one sorted-key JSON line per lease event to a file in the engine's
    data directory, so an operator can reconstruct lease history from a file
    that outlives the process. Delegates to the wind-down audit writer, which
    creates the parent directory and **never raises** (an ``OSError`` is
    swallowed and surfaced as ``False``).
    """

    def __init__(self, path: "str | Path | None", *, clock: Callable[[], float] = time.time) -> None:
        self._path = None if path is None else Path(path)
        self._clock = clock

    @property
    def path(self) -> "Path | None":
        return self._path

    def record(
        self,
        event: str,
        lease: Lease,
        *,
        reason: str | None = None,
        extra: dict | None = None,
    ) -> bool:
        """Append one audit line for ``lease``; ``False`` when nothing was written."""

        if self._path is None:
            return False
        payload: dict[str, Any] = {
            "event": event,
            "lease_id": lease.lease_id,
            "cycle_id": lease.cycle_id,
            "acquired_at": lease.acquired_at,
            "expires_at": lease.expires_at,
            "status": LeaseStatus(lease.status).value,
        }
        if reason is not None:
            payload["reason"] = reason
        if extra:
            payload.update(extra)
        return write_shutdown_audit(self._path, payload, clock=self._clock)


class MaintainerEngine:
    """Runs maintainer cycles, preempting lower-priority monitoring."""

    def __init__(
        self,
        curriculum: TaskCurriculum,
        config: MaintainerConfig | None = None,
        clock: Callable[[], float] = time.monotonic,
        probe: Callable[[str], bool] | None = None,
        signal_gate: Callable[[str], Any] | None = None,
        memory: "MemoryStore | None" = None,
        sleep: Callable[[float], Any] | None = None,
        state_root: "str | Path | None" = None,
        shutdown_audit_path: "str | Path | None" = None,
        role_resolver: Callable[[TaskSpec], str | None] | None = None,
        gate: CycleAdmissionGate | None = None,
        watcher: Any | None = None,
        heartbeat_path: "str | Path | None" = None,
        lease_ttl_sec: float | None = None,
        lease_audit_path: "str | Path | None" = None,
        framework_idle: Callable[[], bool] | None = None,
    ) -> None:
        self._curriculum = curriculum
        self._config = config if config is not None else curriculum.config
        self._clock = clock
        self._lock = threading.RLock()
        # UTILTOOLS-77 REQ-05: the monitored slot is per cycle, so two
        # overlapping cycles cannot retire or preempt each other's task. The
        # ``_monitored`` property keeps the legacy single-cycle view intact.
        self._monitored_by_cycle: dict[int, TaskSpec] = {}
        # The slot of the last cycle to finish alone is carried into the next
        # cycle, so a single-cycle caller still sees the historical "the
        # monitored task survives between cycles" behaviour (and can be
        # preempted by a harder task in the following cycle).
        self._carried_monitored: TaskSpec | None = None
        self._cycle_contexts: dict[int, _CycleContext] = {}
        self._cycle_active_ids: dict[int, tuple[str, ...]] = {}
        self._active_cycles = 0
        self._events: deque[dict[str, Any]] = deque(maxlen=EVENT_LOG_MAXLEN)
        self._cycles = 0
        self._buckets: dict[str, FingerprintBucket] = {}
        self._task_fingerprint: dict[str, str] = {}
        self._probe = probe
        self._signal_gate = signal_gate
        # UTILTOOLS-72 REQ-03: injected memory store (``None`` disables it) and
        # the issues its last consistency check found.
        self._memory = memory
        self._last_cycle_issues: list[str] = []
        self._scheduler_thread: threading.Thread | None = None
        self._scheduler_stop = threading.Event()
        # UTILTOOLS-73: graceful wind-down seams. ``_sleep`` is injected so the
        # bounded wait is testable with a fake clock; ``_cycle_done`` is the
        # checkpoint a wind-down waits on (set = no cycle in flight).
        self._sleep: Callable[[float], Any] = sleep if sleep is not None else time.sleep
        self._cycle_done = threading.Event()
        self._cycle_done.set()
        self._stop = threading.Event()
        # UTILTOOLS-76: task-level cancel is a *separate* flag from the engine
        # stop signal, so cancelling one task never shuts the engine down; both
        # reasons are kept for the STOP_HONOURED / TASK_CANCELLED events.
        self._cancel = threading.Event()
        self._cancel_reason = DEFAULT_CANCEL_REASON
        self._stop_reason = DEFAULT_STOP_REASON
        self._state = EngineState.IDLE
        self._state_root: Path | None = (
            Path(state_root) if state_root is not None else None
        )
        self._shutdown_audit_path: Path | None = (
            Path(shutdown_audit_path) if shutdown_audit_path is not None else None
        )
        self._shutdown_lock = threading.Lock()
        self._shutdown_report: ShutdownReport | None = None
        self._file_locks: dict[str, Any] = {}
        self._active_roles: dict[str, str] = {}
        self._role_resolver = role_resolver
        # UTILTOOLS-77 REQ-01/REQ-02: slot-counted admission, plus the two
        # read-only seams a cycle snapshots (the watcher view and the
        # caller-supplied heartbeat document).
        self._gate = gate if gate is not None else CycleAdmissionGate(
            self._config.max_concurrent_cycles,
            sleep=self._sleep,
            enforce_priority=lambda: bool(
                self._config.priority_preemption_enabled
            ),
        )
        self._watcher = watcher
        self._heartbeat_path: Path | None = (
            Path(heartbeat_path) if heartbeat_path is not None else None
        )
        # UTILTOOLS-78: execution leases (REQ-01..05). ``_lease_lock`` is a
        # plain Lock, never the mid-cycle RLock: acquire/release must be atomic
        # on their own and INV-LEASE-2 forbids nesting the two locks. The
        # reporter keeps lease history in the engine's data directory, which
        # outlives the process: ``<state_root>/maintainer_leases.log``, else
        # ``<repo>/data/maintainer_leases.log``.
        if lease_ttl_sec is None:
            ttl = DEFAULT_LEASE_TTL_SEC
        else:
            ttl = float(lease_ttl_sec)
            if not math.isfinite(ttl) or ttl <= 0:
                raise ValueError("lease_ttl_sec must be a finite float > 0")
        self._lease_ttl_sec = ttl
        self._lease_lock = threading.Lock()
        self._lease_cond = threading.Condition(self._lease_lock)
        self._leases: dict[str, Lease] = {}
        self._active_lease: Lease | None = None
        self._lease_owner: int | None = None
        self._lease_audit_path: Path | None = (
            Path(lease_audit_path)
            if lease_audit_path is not None
            else (
                self._state_root / LEASE_AUDIT_NAME
                if self._state_root is not None
                # No state root: the repo's own data directory, the same
                # location the wind-down audit journal uses.
                else default_audit_path().parent / LEASE_AUDIT_NAME
            )
        )
        # REQ-04's "framework idle check" seam. The engine has no framework
        # predicate of its own, so unset means "always idle" — exactly the
        # pre-task behaviour for every existing call site.
        self._framework_idle: Callable[[], bool] = (
            framework_idle if framework_idle is not None else (lambda: True)
        )
        # REQ-05: public, so an operator (or a test) can re-point the reporter.
        self.reporter = LeaseAuditReporter(self._lease_audit_path)

    # ------------------------------------------------------------------ public

    @property
    def lock(self) -> threading.RLock:
        return self._lock

    @property
    def config(self) -> MaintainerConfig:
        """The live config; ``self.config.max_shutdown_wait_sec`` is the bound."""

        return self._config

    @property
    def file_locks(self) -> tuple[str, ...]:
        """Keys of the still-tracked ``util.fs.file_lock`` instances."""

        with self._lock:
            return tuple(self._file_locks)

    @property
    def events(self) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._events)

    @property
    def signal_gate(self) -> Callable[[str], Any] | None:
        """The installed REQ-03 admit-hook, if any."""

        return self._signal_gate

    def install_signal_gate(self, gate: Callable[[str], Any] | None) -> None:
        """Install the REQ-03 admit-hook on an engine built without one.

        ``serve()`` constructs the engine before the consistency service exists,
        so the wiring seam is a setter rather than a second constructor
        argument; passing ``None`` uninstalls the gate (unconditional admission,
        the pre-task behaviour).
        """

        with self._lock:
            self._signal_gate = gate

    def install_memory(self, memory: "MemoryStore | None") -> None:
        """Install the REQ-03 memory store (``None`` uninstalls it).

        Same seam as ``install_signal_gate()``: the API layer builds the engine
        before the state root is known, and tests inject a ``tmp_path`` store.
        """

        with self._lock:
            self._memory = memory

    def install_watcher(self, watcher: "Any | None") -> None:
        """Install the REQ-02 watcher view (``None`` uninstalls it).

        Plan §4.3 seam, mirroring ``install_memory()``: the API layer builds the
        engine before the monitor snapshot exists, so the read-only view a cycle
        snapshots must be attachable after construction. The watcher is only
        read (`get_snapshot()`), never written.
        """

        with self._lock:
            self._watcher = watcher

    def install_heartbeat_path(self, heartbeat_path: "str | Path | None") -> None:
        """Install the REQ-02 heartbeat document path (``None`` uninstalls it).

        Plan §4.3 seam: without a path the engine captures ``None`` and never
        reads or creates a heartbeat file, which is the pre-task behaviour
        (INV-CONC-5).
        """

        with self._lock:
            self._heartbeat_path = (
                Path(heartbeat_path) if heartbeat_path is not None else None
            )

    def _allocate_cycle_id(self) -> int:
        """Reserve the next monotonic cycle id under ``_lock`` (REQ-05)."""

        with self._lock:
            self._cycles += 1
            return self._cycles

    # ------------------------------------------------- concurrency plumbing

    def _admission_difficulty(self) -> Difficulty:
        """Difficulty of the next task this cycle would pick (admission peek).

        Read-only (INV-CONC-6): the selection runs in ``peek_only`` mode, so it
        stamps no ``pending_since`` and appends no ``FINGERPRINT_SKIPPED``
        event - the peek happens *before* the cycle's snapshot, so any mutation
        here would be invisible to the restore and would leak into a peer. The
        hardest candidate wins, so a queued cycle is admitted with the weight of
        the work it is about to do (REQ-03).
        """

        with self._lock:
            now = self._clock()
            try:
                candidate = self._select_next_task(now, None, peek_only=True)
            except Exception:  # pragma: no cover - defensive
                return Difficulty.EASY
            if candidate is None:
                return Difficulty.EASY
            try:
                return Difficulty.parse(candidate.difficulty)
            except (AttributeError, ValueError):  # pragma: no cover - defensive
                return Difficulty.EASY

    def _capture_snapshot(
        self, *, cycle: int | None, difficulty: Difficulty
    ) -> CycleSnapshot:
        """Capture the state a cycle must not leak into a peer (REQ-02).

        A failed store capture is recorded as ``records=None`` ("unknown"), not
        as an empty document: the restore half must be able to tell "the store
        could not be read" from "the store was empty", otherwise a failed
        capture would blank the whole memory document on restore (v2 finding 2).
        """

        with self._lock:
            memory = self._memory
            watcher = self._watcher
            heartbeat_path = self._heartbeat_path
        records: dict[str, list[Any]] | None = None
        if memory is not None:
            try:
                records = memory.snapshot_records()
            except Exception:  # pragma: no cover - defensive
                records = None
        active_ids: tuple[str, ...] = ()
        if watcher is not None:
            try:
                # Plan §4.3 / v3 finding 1: read the *production* watcher API -
                # ``FrameworkWatcher.get_snapshot().active_tasks`` - instead of
                # a duck-typed ``active_task_ids`` attribute the only shipped
                # watcher class does not have (which made this capture a silent
                # no-op). ``ActiveTask`` carries ``task_id``; it is the active
                # task id the snapshot restores into ``_cycle_active_ids``.
                active_ids = tuple(
                    str(task.task_id)
                    for task in watcher.get_snapshot().active_tasks
                )
            except Exception:  # pragma: no cover - defensive
                active_ids = ()
        return CycleSnapshot(
            cycle=-1 if cycle is None else int(cycle),
            records=records,
            active_task_ids=active_ids,
            heartbeat=read_heartbeat(heartbeat_path),
            admitted_at=self._clock(),
        )

    def _restore_snapshot(
        self,
        snapshot: CycleSnapshot,
        *,
        trusted_cycle: int,
        committed: bool,
    ) -> None:
        """Restore ``snapshot`` after a cycle, whatever its outcome (REQ-02).

        Two modes, chosen under the lock by how many cycles are still active:

        ``exact``
            no peer cycle is running, so the store is put back exactly as it was
            captured - unless the cycle committed, in which case its own records
            are the legitimate product of the cycle and are kept.
        ``cycle-scoped``
            peers are running, so only the records stamped with *this* cycle are
            removed (and only when it did not commit): a peer's records are
            never touched, which is what keeps overlapping cycles from
            corrupting each other.
        The mode decision and the store mutation happen inside the **same**
        ``_lock`` critical section, so a peer cannot register in a window where
        the mode was chosen for "alone" but the store mutation runs with peers
        live (v2 finding 6). Lock order is gate condition -> ``self._lock`` ->
        store ``_write_lock``; nothing takes a store lock while holding the
        engine lock's inverse, and the store never calls back into the engine.
        """

        error: str | None = None
        removed = 0
        # Heartbeat capture is restored outside the lock (file I/O must not
        # serialize peers); the store half is the part that needs atomicity.
        with self._lock:
            memory = self._memory
            peers = self._active_cycles - 1
            mode = "exact" if peers <= 0 else "cycle-scoped"
            if memory is not None and not committed:
                if mode == "exact" and snapshot.records is None:
                    # Capture failed: refuse to touch the store at all rather
                    # than roll it back to "unknown" (v2 finding 2).
                    error = "SNAPSHOT_UNAVAILABLE"
                elif mode == "exact":
                    try:
                        before = memory.snapshot_records()
                        removed = _record_delta(before, snapshot.records)
                        memory.restore_records(snapshot.records)
                    except ValueError:
                        error = "MALFORMED_SNAPSHOT"
                    except Exception:  # pragma: no cover - defensive
                        error = "RESTORE_FAILED"
                else:
                    try:
                        removed = memory.drop_records(cycle=trusted_cycle)
                    except Exception:  # pragma: no cover - defensive
                        error = "RESTORE_FAILED"
        heartbeat_result: bool | None = None
        if snapshot.heartbeat is not None:
            path, data = snapshot.heartbeat
            if data is not None and current_heartbeat(path) != data:
                heartbeat_result = write_heartbeat(path, data)
                if heartbeat_result is False and error is None:
                    # v3 finding 5: a failed restore must not be reported as a
                    # clean one. ``write_heartbeat`` returns ``False`` when the
                    # atomic swap could not happen (missing directory, symlink
                    # swap, ``OSError``); surface it as a restore failure so the
                    # audit line is ``SNAPSHOT_RESTORE_FAILED`` with
                    # ``heartbeat_restored: false`` instead of a success.
                    error = "HEARTBEAT_RESTORE_FAILED"
        # Stay quiet when the restore was a no-op: an uncontended, committed
        # cycle leaves the store exactly as captured, and emitting
        # ``SNAPSHOT_RESTORED`` for it would append an event after the cycle's
        # own CONSISTENCY event that existing callers assert on (v2 finding 1:
        # no observable change for callers that run one cycle at a time).
        if error is not None or removed or heartbeat_result is not None or mode != "exact":
            with self._lock:
                self._events.append(
                    {
                        "event": (
                            "SNAPSHOT_RESTORED"
                            if error is None
                            else "SNAPSHOT_RESTORE_FAILED"
                        ),
                        "cycle": trusted_cycle,
                        "mode": mode,
                        "committed": bool(committed),
                        "removed": int(removed),
                        "error": error,
                        "heartbeat_restored": heartbeat_result,
                    }
                )

    def _register_cycle(
        self,
        cycle: int,
        difficulty: Difficulty,
        snapshot: CycleSnapshot,
    ) -> None:
        """Enter the cycle in the registry under its pre-allocated id (REQ-05).

        The id comes from :meth:`_allocate_cycle_id` *before* the gate wait, so
        the waiter label, the cycle-scoped restore and ``CycleResult.cycle`` all
        name the same cycle; registering never re-derives a second id.
        """

        with self._lock:
            self._cycles = max(self._cycles, int(cycle))
            # The next cycle inherits the slot of the last cycle that finished
            # alone, so single-cycle callers keep the historical behaviour
            # (preemption is evaluated against the still-monitored task). The
            # adoption happens inside the body's first lock hold, never here,
            # so a reader can never observe a carried task as monitored.
            self._cycle_contexts[int(cycle)] = _CycleContext(
                cycle=int(cycle),
                difficulty=int(Difficulty.parse(difficulty)),
                thread=threading.get_ident(),
                admitted_at=snapshot.admitted_at,
            )
            self._cycle_active_ids[int(cycle)] = tuple(snapshot.active_task_ids)
            self._active_cycles += 1
            self._cycle_done.clear()

    def _deregister_cycle(self, cycle: int) -> None:
        """Leave the registry; the latch drops when the last cycle leaves."""

        with self._lock:
            self._cycle_contexts.pop(cycle, None)
            monitored = self._monitored_by_cycle.pop(cycle, None)
            if monitored is not None and self._active_cycles <= 1:
                # Last cycle out hands its monitored task to the next cycle
                # instead of dropping it (no peer can be using the slot).
                self._carried_monitored = monitored
            self._cycle_active_ids.pop(cycle, None)
            self._active_cycles = max(0, self._active_cycles - 1)
            if self._active_cycles == 0:
                self._cycle_done.set()

    def _sync_cycle_done(self) -> None:
        """Re-derive the idle latch from the active-cycle count (REQ-04)."""

        with self._lock:
            if self._active_cycles == 0:
                self._cycle_done.set()
            else:
                self._cycle_done.clear()

    @property
    def active_cycles(self) -> int:
        """How many cycle bodies are in flight right now (REQ-05)."""

        with self._lock:
            return self._active_cycles

    def cycle_task_ids(self, cycle: int) -> tuple[str, ...]:
        """Watcher task IDs captured for ``cycle`` (empty when unknown)."""

        with self._lock:
            return tuple(self._cycle_active_ids.get(int(cycle), ()))

    # ------------------------------------------------------- leases (REQ-01..05)

    def acquire_lease(
        self,
        cycle_id: str,
        *,
        block: bool = True,
        timeout: float | None = None,
    ) -> Lease:
        """Grant the single execution slot to ``cycle_id`` (REQ-02).

        Blocks while another cycle holds an ``ACTIVE`` lease (``block=True``,
        the default) so a second cycle can never enter the body concurrently.
        ``block=False`` refuses immediately with :class:`LeaseHeldError`; a
        ``timeout`` that elapses raises :class:`LeaseTimeoutError` — the
        documented escape hatch is the reaper, which frees an expired lease.
        """

        if not isinstance(cycle_id, str) or not cycle_id:
            raise ValueError("cycle_id must be a non-empty str")
        with self._lease_cond:
            if self._active_lease is not None:
                if not block:
                    raise LeaseHeldError(cycle_id, self._active_lease)
                if not self._lease_cond.wait_for(
                    lambda: self._active_lease is None, timeout=timeout
                ):
                    raise LeaseTimeoutError(cycle_id, timeout, self._active_lease)
            acquired_at = self._clock()
            lease = Lease(
                lease_id=uuid.uuid4().hex,
                cycle_id=cycle_id,
                acquired_at=acquired_at,
                expires_at=acquired_at + self._lease_ttl_sec,
            )
            self._leases[lease.lease_id] = lease
            self._active_lease = lease
            self._lease_owner = threading.get_ident()
            self._prune_lease_history()
            self._lease_cond.notify_all()
        # INV-LEASE-4: the audit write happens outside the lease lock.
        self._report(LEASE_ACQUIRED, lease)
        return lease

    def release_lease(self, lease_id: str) -> Lease:
        """Release an ``ACTIVE`` lease, making the slot available again (REQ-02).

        A refused transition (unknown id, already terminal, wrong thread) never
        changes ``status``: it is audited once as ``LEASE_REFUSED`` and raised.
        """

        lease_id = str(lease_id)
        refusal_reason: str | None = None
        error: LeaseError | None = None
        released: Lease | None = None
        with self._lease_cond:
            lease = self._leases.get(lease_id)
            if lease is None:
                refusal_reason = "unknown_lease"
                error = LeaseTransitionError(lease_id, "unknown lease id")
            elif lease.status is not LeaseStatus.ACTIVE:
                refusal_reason = "already_released"
                error = LeaseTransitionError(
                    lease_id, f"lease is already {LeaseStatus(lease.status).value}"
                )
            elif self._lease_owner != threading.get_ident():
                refusal_reason = "not_held"
                error = LeaseNotHeldError(lease_id, "lease is held by another thread")
            else:
                released = replace(lease, status=LeaseStatus.RECLAIMED)
                self._leases[lease_id] = released
                self._active_lease = None
                self._lease_owner = None
                self._prune_lease_history()
                self._lease_cond.notify_all()
        if error is not None:
            self._report(
                LEASE_REFUSED,
                self._leases.get(lease_id) or self._placeholder_lease(lease_id),
                reason=refusal_reason,
            )
            raise error
        assert released is not None
        self._report(LEASE_RELEASED, released, reason="released")
        return released

    def _reap_stale_leases(self, now: float | None = None) -> ReapReport:
        """Move every expired ``ACTIVE`` lease to ``RECLAIMED`` (REQ-03).

        One pass under the lease condition: ``ACTIVE`` + ``now > expires_at``
        -> ``EXPIRED`` (``ttl_elapsed``) -> ``RECLAIMED`` (``stale``). The
        compare-and-set happens inside one critical section, so a reaper racing
        a legitimate release can never double-emit a terminal event; a lease
        that is already terminal (an already-completed cycle) is counted in
        ``skipped`` and produces no audit line at all.
        """

        expired_leases: list[Lease] = []
        reclaimed_leases: list[Lease] = []
        skipped = 0
        with self._lease_cond:
            at = self._clock() if now is None else float(now)
            for lease_id, lease in list(self._leases.items()):
                if not lease.is_expired(at):
                    skipped += 1
                    continue
                expired = replace(lease, status=LeaseStatus.EXPIRED)
                self._leases[lease_id] = expired
                expired_leases.append(expired)
                if self._active_lease is not None and self._active_lease.lease_id == lease_id:
                    self._active_lease = None
                    self._lease_owner = None
                reclaimed = replace(expired, status=LeaseStatus.RECLAIMED)
                self._leases[lease_id] = reclaimed
                reclaimed_leases.append(reclaimed)
            self._prune_lease_history()
            self._lease_cond.notify_all()
        # INV-LEASE-4: audit writes and memory maintenance run outside both locks.
        for expired, reclaimed in zip(expired_leases, reclaimed_leases):
            self._report(LEASE_EXPIRED, expired, reason="ttl_elapsed")
            self._report(LEASE_RECLAIMED, reclaimed, reason="stale")
        if reclaimed_leases and self._memory is not None:
            self._prune_memory(self._memory)
        return ReapReport(
            at=at,
            reaped=[lease.lease_id for lease in reclaimed_leases],
            skipped=skipped,
        )

    def lease_snapshot(self) -> list[dict]:
        """Read-only view of the lease registry; performs no I/O (DOD-12)."""

        with self._lease_lock:
            return [lease.to_dict() for lease in self._leases.values()]

    def _require_active_lease(self) -> Lease:
        """Return the lease this thread holds, else refuse the body (REQ-04)."""

        with self._lease_lock:
            lease = self._active_lease
            owner = self._lease_owner
        if lease is None or lease.status is not LeaseStatus.ACTIVE:
            raise LeaseNotHeldError(None, "cycle body entered without an active lease")
        if owner != threading.get_ident():
            raise LeaseNotHeldError(
                lease.lease_id, "cycle body entered without an active lease"
            )
        return lease

    def _next_cycle_id(self) -> str:
        """The id of the cycle the body is about to run, as a stable preview."""

        with self._lock:
            return f"cycle-{self._cycles + 1}"

    def _prune_lease_history(self) -> None:
        """Drop the oldest terminal leases past ``LEASE_HISTORY_MAXLEN``.

        Callers hold :attr:`_lease_cond`. The ``ACTIVE`` lease is never a
        prune candidate, so bounded history (INV-LEASE-6) can never evict the
        lease a cycle currently holds.
        """

        if len(self._leases) <= LEASE_HISTORY_MAXLEN:
            return
        for lease_id in list(self._leases):
            if len(self._leases) <= LEASE_HISTORY_MAXLEN:
                break
            lease = self._leases[lease_id]
            if lease.status is LeaseStatus.ACTIVE:
                continue
            del self._leases[lease_id]

    def _report(
        self,
        event: str,
        lease: Lease,
        *,
        reason: str | None = None,
        extra: dict | None = None,
    ) -> bool:
        """Send one lease event to ``self.reporter`` (REQ-05); never raises."""

        reporter = self.reporter
        if reporter is None:  # pragma: no cover - defensive, default is set
            return False
        return reporter.record(event, lease, reason=reason, extra=extra)

    @staticmethod
    def _placeholder_lease(lease_id: str) -> Lease:
        """Filler record for a refusal audit line about an unknown lease id."""

        return Lease(
            lease_id=str(lease_id),
            cycle_id="",
            acquired_at=0.0,
            expires_at=0.0,
            status=LeaseStatus.RECLAIMED,
        )

    def run_cycle(self) -> CycleResult:
        """Execute at most ``max_steps_per_cycle`` monitor steps.

        Two admission layers, outermost last:

        * UTILTOOLS-78 lease frame (REQ-04): after the framework idle check a
          lease is acquired, the body runs under it, and one ``finally``
          releases it on *every* exit path - success, body exception, timeout
          or stop - so a cycle can never leak its slot.
        * UTILTOOLS-77 concurrency frame (REQ-01/REQ-02): a slot from the
          admission gate (bounded by ``max_concurrent_cycles``), then a snapshot
          of the shared state a peer could see, restored on completion *and* on
          failure.

        The cycle id is allocated under ``_lock`` *before* the gate wait, so the
        waiter label, the ``CYCLE_DEFERRED`` event, the lease id and the
        eventual ``CycleResult.cycle`` are one and the same number (REQ-05
        attribution).
        """

        lease: Lease | None = None
        token = None
        snapshot: CycleSnapshot | None = None
        cycle: int | None = None
        committed = False
        try:
            if not self._framework_idle():
                # Read the counter under the same lock ``_next_cycle_id`` uses
                # so the idle-gate report cannot tear against the acquire path.
                with self._lock:
                    current_cycle = self._cycles
                return CycleResult(
                    cycle=current_cycle,
                    monitored=None,
                    priority=None,
                    preempted=None,
                    steps=0,
                )
            cycle = self._allocate_cycle_id()
            lease = self.acquire_lease(f"cycle-{cycle}")
            difficulty = self._admission_difficulty()
            token = self._gate.acquire(
                difficulty, cycle, should_stop=self.should_stop
            )
            if token is None:
                # A stop was published while queued: report a deferred cycle
                # instead of running one nobody asked for.
                with self._lock:
                    self._events.append(
                        {
                            "event": "CYCLE_DEFERRED",
                            "cycle": cycle,
                            "reason": "STOP_REQUESTED",
                            "difficulty": Difficulty.parse(difficulty).name,
                        }
                    )
                return CycleResult(
                    cycle=cycle,
                    monitored=None,
                    priority=None,
                    preempted=None,
                    steps=0,
                    admitted=False,
                )
            snapshot = self._capture_snapshot(cycle=cycle, difficulty=difficulty)
            self._register_cycle(cycle, difficulty, snapshot)
            result = self._run_cycle_body(cycle=cycle)
            committed = True
            return result
        finally:
            try:
                if snapshot is not None and cycle is not None:
                    self._restore_snapshot(
                        snapshot, trusted_cycle=cycle, committed=committed
                    )
                    self._deregister_cycle(cycle)
                if token is not None:
                    self._gate.release(token)
            finally:
                if lease is not None:
                    try:
                        self.release_lease(lease.lease_id)
                    except LeaseError:
                        # A refused transition is already audited; the exit path
                        # must never mask the body's own outcome.
                        pass

    # ------------------------------------------------------- wind-down (live)

    @property
    def state(self) -> EngineState:
        with self._lock:
            return self._state

    def is_shutting_down(self) -> bool:
        with self._lock:
            return self._state is EngineState.SHUTTING_DOWN

    def request_stop(self, reason: str = DEFAULT_STOP_REASON) -> None:
        """Publish the stop signal the cycle loop polls (idempotent).

        ``reason`` is remembered for the ``STOP_HONOURED`` event and the log
        line ``run_forever()`` emits, so a wind-down is always attributable
        (REQ-04). The first reason wins: a later ``SIGTERM`` cannot overwrite
        the operator's stated reason.
        """

        with self._lock:
            already = self._stop.is_set()
            if not already:
                self._stop_reason = str(reason)
            self._stop.set()
            if not already:
                self._events.append(
                    {
                        "event": "STOP_REQUESTED",
                        "cycle": self._cycles,
                        "state": self._state.value,
                        "reason": self._stop_reason,
                    }
                )

    def should_stop(self) -> bool:
        """Has a wind-down been requested?"""

        return self._stop.is_set()

    @property
    def stop_reason(self) -> str:
        """Reason recorded by the first :meth:`request_stop` call."""

        with self._lock:
            return self._stop_reason

    @property
    def pid_file(self) -> Path | None:
        """``<state_root>/maintainer.pid``, or ``None`` without a state root."""

        return self._runtime_path(RUNTIME_PID_FILE)

    @property
    def heartbeat_file(self) -> Path | None:
        """``<state_root>/maintainer.heartbeat``, or ``None`` without a root."""

        return self._runtime_path(RUNTIME_HEARTBEAT_FILE)

    def should_cancel(self) -> bool:
        """Has the monitored task been cancelled by :meth:`cancel_task`?"""

        return self._cancel.is_set()

    def _wind_down_requested(self) -> bool:
        """Stop flag or cancel flag — the state behind :meth:`should_stop`.

        Read without the public predicates so a cycle never invokes an
        overridden ``should_stop()`` once per step; ``run_forever()`` stays the
        single poller of the predicate.
        """

        return self._stop.is_set() or self.should_cancel()

    def cancel_task(
        self,
        timeout_sec: float | None = None,
        *,
        reason: str = DEFAULT_CANCEL_REASON,
    ) -> CancelReport:
        """Cancel the monitored task and wait for its cycle to unwind (REQ-01).

        ``timeout_sec`` bounds the wait and defaults to
        ``config.max_shutdown_wait_sec``, the same bound a wind-down uses.
        Returns only after the in-flight cycle has left ``run_cycle()`` (or the
        bound expired), so a caller never observes a half-cancelled task.
        """

        timeout = self._cancel_timeout(timeout_sec)
        started = self._clock()
        with self._lock:
            task = self._monitored
            if task is None or task.state not in NON_TERMINAL:
                self._events.append(
                    {
                        "event": "CANCEL_NOOP",
                        "cycle": self._cycles,
                        "task": None if task is None else task.name,
                        "reason": str(reason),
                    }
                )
                return CancelReport(
                    task=None if task is None else task.name,
                    state=None if task is None else task.state.value,
                    cancelled=False,
                    timed_out=False,
                    waited_sec=0.0,
                    reason=str(reason),
                )
            name = task.name
            self._cancel_reason = str(reason)
            task.state = TaskState.CANCELLED
            task.pending_since = None
            # REQ-05: cancel clears the task from *its* cycle slot(s) instead of
            # one global slot, so a peer cycle tracking a different task keeps
            # tracking it. The carried slot is dropped too when it is the
            # cancelled task (the legacy single-cycle view must not resurrect
            # a CANCELLED task).
            for slot, bound in list(self._monitored_by_cycle.items()):
                if bound is task:
                    self._monitored_by_cycle.pop(slot, None)
            if self._carried_monitored is task:
                self._carried_monitored = None
            self._cancel.set()
            self._events.append(
                {
                    "event": "TASK_CANCEL_REQUESTED",
                    "cycle": self._cycles,
                    "task": name,
                    "reason": self._cancel_reason,
                }
            )

        # INV-CANCEL-1: the wait holds no engine lock (mirrors ``shutdown()``),
        # so the running cycle can still commit its step and reach its break.
        deadline = started + timeout
        timed_out = False
        while not self._cycle_done.is_set():
            remaining = deadline - self._clock()
            if remaining <= 0.0:
                timed_out = True
                break
            self._sleep(min(SHUTDOWN_POLL_INTERVAL_SEC, remaining))
        waited_sec = max(0.0, self._clock() - started)

        with self._lock:
            self._cancel.clear()
            state = task.state.value
            self._events.append(
                {
                    "event": "TASK_CANCELLED",
                    "cycle": self._cycles,
                    "task": name,
                    "state": state,
                    "waited_sec": waited_sec,
                    "timed_out": timed_out,
                    "reason": self._cancel_reason,
                }
            )
        return CancelReport(
            task=name,
            state=state,
            cancelled=True,
            timed_out=timed_out,
            waited_sec=waited_sec,
            reason=self._cancel_reason,
        )

    def note_task_role(self, task: str, role: str) -> None:
        """Remember which role is driving ``task`` (persisted on wind-down)."""

        with self._lock:
            self._active_roles[task] = str(role)

    def register_file_lock(self, key: str, lock: Any) -> None:
        """Track a ``util.fs.file_lock`` so a wind-down can release it."""

        with self._lock:
            previous = self._file_locks.get(key)
            self._file_locks[key] = lock
        if previous is not None and previous is not lock:
            _release_quietly(previous)

    def release_file_lock(self, key: str) -> bool:
        """Release and forget one tracked lock (``False`` when untracked)."""

        with self._lock:
            lock = self._file_locks.pop(key, None)
        if lock is None:
            return False
        if not _release_quietly(lock):
            raise OSError(f"failed to release file lock {key!r}")
        return True

    def release_all_file_locks(self) -> tuple[tuple[str, ...], tuple[str, ...]]:
        """Drain the lock registry; returns ``(released_keys, failures)``.

        Every key is popped *before* its ``release()`` call so a raising lock
        can never be released twice by a later pass.
        """

        with self._lock:
            tracked = list(self._file_locks.items())
            self._file_locks.clear()
        released: list[str] = []
        failures: list[str] = []
        for key, lock in tracked:
            if _release_quietly(lock):
                released.append(key)
            else:
                failures.append(key)
        return tuple(released), tuple(failures)

    def run_forever(self, interval_sec: float | None = None) -> ShutdownReport:
        """Run cycles until ``request_stop()``, then wind down cleanly."""

        interval = self._interval(interval_sec)
        self._stop.clear()
        with self._lock:
            self._state = EngineState.IDLE
        # REQ-03 / INV-MARK-1: markers exist only with a state root, so an
        # in-process caller that passes none leaves the repo untouched.
        self._write_runtime_markers()
        try:
            while True:
                if self.should_stop():
                    break
                with self._lock:
                    if self._state is EngineState.STOPPED:
                        self._state = EngineState.IDLE
                    self._state = EngineState.RUNNING
                    self._cycle_done.clear()
                try:
                    self.run_cycle()
                finally:
                    self._cycle_done.set()
                    with self._lock:
                        if self._state is EngineState.RUNNING:
                            self._state = EngineState.IDLE
                self._write_runtime_markers()
                if self.should_stop():
                    break
                # REQ-04: reclaim an expired lease left by a stalled cycle, so
                # the next acquire is not parked behind a dead holder.
                self._reap_stale_leases()
                with self._lock:
                    if self._state is EngineState.IDLE:
                        self._state = EngineState.SLEEPING
                if self.should_stop():
                    break
                self._pause(interval)
        finally:
            self._log_stop_reason()
            report = self.shutdown()
            # INV-MARK-4 / DOD-03: the markers are published at entry, but
            # ``shutdown()`` is idempotent and returns its cached report on a
            # second ``run_forever()`` without re-running the marker cleanup.
            # Clearing here (a no-op when ``shutdown()`` already did it) keeps
            # the loop's exit free of stale ``maintainer.pid``/heartbeat files
            # no matter how many times it is entered.
            self._clear_runtime_markers()
            return report

    def shutdown(self) -> ShutdownReport:
        """Bounded, idempotent wind-down (INV-SHUT-1/2/3/4/5)."""

        with self._shutdown_lock:
            if self._shutdown_report is not None:
                return self._shutdown_report
            with self._lock:
                self._state = EngineState.SHUTTING_DOWN
                active = [
                    task
                    for task in self._curriculum.specs
                    if task.state in (TaskState.RUNNING, TaskState.IN_PROGRESS)
                ]
                active_ids = tuple(task.name for task in active)
                self._events.append(
                    {
                        "event": "SHUTTING_DOWN",
                        "cycle": self._cycles,
                        "active_task_ids": list(active_ids),
                    }
                )
                started = self._clock()
                deadline = started + float(self._config.max_shutdown_wait_sec)

            # INV-SHUT-1: the wait loop holds no engine lock, so a cycle that is
            # mid-commit can still acquire ``_lock`` and reach its break point.
            timed_out = False
            while not self._cycle_done.is_set():
                remaining = deadline - self._clock()
                if remaining <= 0.0:
                    timed_out = True
                    break
                self._sleep(min(SHUTDOWN_POLL_INTERVAL_SEC, remaining))
            waited_sec = max(0.0, self._clock() - started)

            cleanup_steps: list[str] = []
            failed_steps: list[str] = []
            persisted: list[str] = []
            persist_failures: list[str] = []
            persist_skipped_reason: str | None = None

            try:
                self.stop_scheduler()
            except Exception:
                failed_steps.append(CLEANUP_STOP_SCHEDULER)
            else:
                cleanup_steps.append(CLEANUP_STOP_SCHEDULER)

            if self._state_root is None:
                persist_skipped_reason = PERSIST_SKIPPED_NO_STATE_ROOT
            else:
                for task in active:
                    role, role_source = self._resolve_role(task)
                    payload = {
                        "schema": SHUTDOWN_STATE_SCHEMA,
                        "task": task.name,
                        "role": role,
                        "role_source": role_source,
                        "status": task.state.value,
                        "progress": task.progress,
                        "steps_required": task.steps_required,
                        "timeline": {
                            "cycle": self._cycles,
                            "pending_since": task.pending_since,
                            "last_progress_at": task.last_progress_at,
                            "last_progress_age_sec": progress_age_sec(
                                task, self._clock()
                            ),
                            "stale_since": task.stale_since,
                            "preempted_count": task.preempted_count,
                        },
                        "elapsed_shutdown_wait_sec": waited_sec,
                        "shutdown_started_at": started,
                        "timed_out": timed_out,
                        "cycle": self._cycles,
                    }
                    try:
                        write_shutdown_state(
                            self._state_root / task.name, self._state_root, payload
                        )
                    except (OSError, ValueError):
                        persist_failures.append(task.name)
                    else:
                        persisted.append(task.name)
                if persist_failures:
                    failed_steps.append(CLEANUP_PERSIST_TASK_STATE)
                else:
                    cleanup_steps.append(CLEANUP_PERSIST_TASK_STATE)

            released, lock_failures = self.release_all_file_locks()
            if lock_failures:
                failed_steps.append(CLEANUP_RELEASE_FILE_LOCKS)
            else:
                cleanup_steps.append(CLEANUP_RELEASE_FILE_LOCKS)

            # REQ-03 / INV-PRUNE-4: prune after the locks are released, before
            # the audit record is built, so the record can name the drop counts.
            pruned_slots: list[str] = []
            try:
                pruned_slots = self._prune_stale_task_slots()
            except Exception:  # pragma: no cover - defensive
                failed_steps.append(CLEANUP_PRUNE_STALE_TASK_SLOTS)
            else:
                cleanup_steps.append(CLEANUP_PRUNE_STALE_TASK_SLOTS)

            memory_dropped = {"runs": 0, "bug_fixes": 0, "learnings": 0}
            memory = self._memory
            if memory is not None:
                with self._lock:
                    known_task_ids = {task.name for task in self._curriculum.specs}
                # INV-PRUNE-1/4: ``prune()`` only mutates the in-process store,
                # so the wind-down must persist right after it — otherwise the
                # document on disk keeps every record while the audit line
                # below advertises drops it never wrote (REQ-03/DOD-07).
                memory_dropped, prune_issue = self._prune_memory(memory)
                save_error: Exception | None = None
                try:
                    memory.save(known_task_ids=known_task_ids)
                except Exception as error:  # pragma: no cover - defensive
                    save_error = error
                if prune_issue is None and save_error is None:
                    cleanup_steps.append(CLEANUP_PRUNE_MEMORY)
                else:
                    # Never raise from bookkeeping: an unpersisted trim is a
                    # failed cleanup step, exactly like a failed prune.
                    failed_steps.append(CLEANUP_PRUNE_MEMORY)

            # INV-MARK-4: marker removal is a cleanup step, so a failure shows
            # up in ``failed_steps`` instead of being silently ignored.
            if self._clear_runtime_markers():
                cleanup_steps.append(CLEANUP_CLEAR_RUNTIME_MARKERS)
            else:  # pragma: no cover - depends on permissions
                failed_steps.append(CLEANUP_CLEAR_RUNTIME_MARKERS)

            # The audit write is itself a cleanup step; it is queued before the
            # record is built so the record can name it (DOD-09).
            cleanup_steps.append(CLEANUP_WRITE_SHUTDOWN_AUDIT)
            record = {
                "schema": "maintainer-shutdown/v1",
                "recorded_at": _wall_clock(),
                "active_task_ids": list(active_ids),
                "cleanup_steps": list(cleanup_steps),
                "failed_steps": list(failed_steps),
                "lock_release_failures": list(lock_failures),
                "persisted_task_ids": list(persisted),
                "persist_failures": list(persist_failures),
                "persist_skipped_reason": persist_skipped_reason,
                "released_lock_keys": list(released),
                "pruned_task_slots": list(pruned_slots),
                "memory_dropped": dict(memory_dropped),
                "runtime_markers_cleared": CLEANUP_CLEAR_RUNTIME_MARKERS
                in cleanup_steps,
                "timed_out": timed_out,
                "waited_sec": waited_sec,
                "max_shutdown_wait_sec": float(self._config.max_shutdown_wait_sec),
                "engine_state": EngineState.STOPPED.value,
            }
            audit_path = self._shutdown_audit_path or default_audit_path()
            audit_written = write_shutdown_audit(audit_path, record)
            if not audit_written:  # pragma: no cover - depends on permissions
                cleanup_steps.remove(CLEANUP_WRITE_SHUTDOWN_AUDIT)
                failed_steps.append(CLEANUP_WRITE_SHUTDOWN_AUDIT)

            with self._lock:
                self._state = EngineState.STOPPED
                self._events.append(
                    {
                        "event": "SHUTDOWN_COMPLETE",
                        "cycle": self._cycles,
                        "timed_out": timed_out,
                        "waited_sec": waited_sec,
                        "persisted_task_ids": list(persisted),
                        "audit_written": audit_written,
                    }
                )
            report = ShutdownReport(
                state=EngineState.STOPPED,
                timed_out=timed_out,
                waited_sec=waited_sec,
                active_task_ids=active_ids,
                persisted_task_ids=tuple(persisted),
                released_lock_keys=released,
                cleanup_steps=tuple(cleanup_steps),
                failed_steps=tuple(failed_steps),
                lock_release_failures=lock_failures,
                persist_skipped_reason=persist_skipped_reason,
                audit_written=audit_written,
                audit_path=str(audit_path),
            )
            self._shutdown_report = report
            return report

    def _run_cycle_body(self, cycle: int | None = None) -> CycleResult:
        """The cycle body itself; ``cycle`` is the id ``run_cycle()`` registered.

        UTILTOOLS-77 REQ-05: the monitored slot is per cycle, so this body reads
        and writes ``_monitored_by_cycle[cycle]`` instead of one shared slot.
        A caller that passes no ``cycle`` gets a fresh registration, which keeps
        the historical single-cycle call shape working.

        REQ-04 enforcement point: no cycle body runs without a lease. A direct
        call (bypassing ``run_cycle``) is refused here.
        """

        lease = self._require_active_lease()
        assert lease.status is LeaseStatus.ACTIVE
        steps = 0
        preempted_name: str | None = None
        monitored_name: str | None = None
        priority_name: str | None = None
        reported_skips: set[str] = set()

        with self._lock:
            if cycle is None:
                self._cycles += 1
                cycle = self._cycles
            # Poll-tick half of the stale-task state machine: refresh every
            # active task's progress age and warn (``STALE_TASK``) on a
            # breach. Recovery is the sweep thread's job, never the tick's.
            self._evaluate_stale(self._clock())

        while steps < self._config.max_steps_per_cycle:
            # REQ-02: a stop or cancel issued while the previous step was in
            # flight is honoured here, before any new step starts. The flags are
            # read directly (they are what ``should_stop()`` reports) so the
            # per-step check stays free of subclass hooks: ``run_forever()``
            # stays the single poller of the public predicate.
            if self._wind_down_requested():
                break
            with self._lock:
                now = self._clock()
                mine = self._monitored_by_cycle.get(cycle)
                if (
                    mine is None
                    and self._carried_monitored is not None
                    and self._active_cycles <= 1
                ):
                    mine = self._monitored_by_cycle[cycle] = self._carried_monitored
                    self._carried_monitored = None
                if mine is not None and mine.state not in NON_TERMINAL:
                    self._monitored_by_cycle.pop(cycle, None)
                    mine = None
                incoming = self._select_next_task(now, reported_skips)
                if mine is None:
                    if incoming is None:
                        break
                    if not self._admit(incoming):
                        reported_skips.add(incoming.name)
                        continue
                    self._adopt(incoming, cycle=cycle)
                elif (
                    incoming is not None
                    and incoming is not mine
                    and self._should_preempt(incoming, mine, now)
                ):
                    if not self._admit(incoming):
                        reported_skips.add(incoming.name)
                        continue
                    previous = mine
                    self._preempt(incoming, previous, cycle=cycle)
                    preempted_name = previous.name
                    self._adopt(incoming, cycle=cycle)
                task = self._monitored_by_cycle.get(cycle)
                if task is None:
                    break
                monitored_name = task.name
                context = self._cycle_contexts.get(cycle)
                if context is not None:
                    context.task = task.name
                priority_name = effective_priority(
                    task, cycle=cycle, now=now, config=self._config
                ).name
                task.state = TaskState.RUNNING
                next_progress = task.progress + 1

            if self._wind_down_requested():
                # No state mutation here: a cancel already parked the task in
                # CANCELLED and a stop leaves it RUNNING so ``shutdown()`` can
                # still persist it as active (INV-CANCEL-4 / REQ-03).
                break
            # Monitor step body runs outside the lock.
            error: BaseException | None = None
            try:
                self._monitor_step(task)
            except Exception as exc:  # pragma: no cover - defensive path
                error = exc

            with self._lock:
                if self._monitored_by_cycle.get(cycle) is not task:
                    # A concurrent cycle preempted or retired it: discard.
                    steps += 1
                    continue
                if error is not None:
                    task.state = TaskState.FAILED
                    task.pending_since = None
                    self._monitored_by_cycle.pop(cycle, None)
                    self._record_cycle_outcome(
                        "BLOCKED", task, cycle=cycle, error=error
                    )
                    steps += 1
                    continue
                task.progress = max(task.progress, next_progress)
                # INV-STALE-5: a committed step *is* a progress line, so the
                # stale clock restarts here, before the outcome is recorded.
                stamp_progress(task, self._clock())
                if task.progress >= task.steps_required:
                    task.state = TaskState.COMPLETED
                    task.pending_since = None
                    self._monitored_by_cycle.pop(cycle, None)
                    self._record_cycle_outcome("COMPLETED", task, cycle=cycle)
                else:
                    self._record_cycle_outcome("PROGRESS", task, cycle=cycle)
            steps += 1

        # REQ-03: every memory record is now updated, so validate the store and
        # persist it before the cycle report is built. Never raises.
        self._finish_cycle(cycle)

        with self._lock:
            memory_installed = self._memory is not None
        return CycleResult(
            cycle=cycle,
            monitored=monitored_name,
            priority=priority_name,
            preempted=preempted_name,
            steps=steps,
            admitted=True,
            memory_cycle=cycle if memory_installed else None,
        )

    def _finish_cycle(self, cycle: int) -> list[str]:
        """Validate and persist the memory records at the end of a cycle.

        Called by ``run_cycle()`` after every ``_record_cycle_outcome()`` call
        and before the ``CycleResult`` report is built, so the report always
        describes a store that has already been checked (REQ-03). The known
        task-id set is copied under ``_lock``; validation and the store write
        run outside it, because they touch the file system. With no memory
        store installed the engine behaves exactly as before.
        """

        with self._lock:
            memory = self._memory
            known_task_ids = {task.name for task in self._curriculum.specs}

        if memory is None:
            with self._lock:
                self._last_cycle_issues = []
            return []

        # INV-PRUNE-2: prune before save, so the persisted document is already
        # trimmed when validation sees it.
        dropped, prune_issue = self._prune_memory(memory)
        try:
            issues = list(memory.save(known_task_ids=known_task_ids))
        except Exception as error:  # pragma: no cover - defensive
            # Memory bookkeeping must never fail a cycle (REQ-03).
            issues = [f"MEMORY_CORRUPT: memory save failed ({type(error).__name__})"]
        if prune_issue is not None:
            issues.insert(0, prune_issue)

        with self._lock:
            self._last_cycle_issues = list(issues)
            if any(dropped.values()):
                self._events.append(
                    {
                        "event": "MEMORY_PRUNED",
                        "cycle": cycle,
                        "dropped": dict(dropped),
                    }
                )
            self._events.append(
                {
                    "event": "CONSISTENCY",
                    "kind": "CONSISTENCY",
                    "cycle": cycle,
                    "codes": list(issues),
                }
            )
        return issues

    def snapshot(self) -> dict:
        """Lock-protected view of the monitored tasks, admission and priorities.

        ``active_cycles`` carries one row per live cycle - ``{cycle, task,
        difficulty, admitted_at}`` - as Plan §4 requires, so an operator can
        attribute a task to the cycle that holds it and see the admission
        weight each cycle was ordered by (REQ-03/REQ-05). ``task`` is the name
        the cycle's slot last adopted (kept after retirement); ``difficulty``
        is the :class:`Difficulty` name of that cycle's admission peek.
        ``admission`` is the gate's own read-only view (limit / active /
        waiting), captured after the engine lock is released so the gate
        condition is never nested inside the engine lock.
        """

        with self._lock:
            now = self._clock()
            monitored = self._monitored
            monitored_by_cycle = {
                str(slot): task.name
                for slot, task in sorted(self._monitored_by_cycle.items())
            }
            active_cycles = [
                {
                    "cycle": context.cycle,
                    "task": context.task,
                    "difficulty": _difficulty_name(context.difficulty),
                    "admitted_at": context.admitted_at,
                    "thread": context.thread,
                }
                for _slot, context in sorted(self._cycle_contexts.items())
            ]
            gate = self._gate
            pending: dict[str, str] = {}
            for task in self._curriculum.specs:
                if task.state not in NON_TERMINAL:
                    continue
                if task.state in STALE_ELIGIBLE:
                    # Held by a cycle, or in flight but retired by one: it is
                    # not waiting for admission, so listing it as pending with
                    # a high priority would misreport a lower-priority task as
                    # starved (DOD-09).
                    continue
                if monitored is not None and task is monitored:
                    continue
                stamp_pending_since(task, now)
                pending[task.name] = effective_priority(
                    task, cycle=self._cycles, now=now, config=self._config
                ).name
            view = {
                "monitored": None if monitored is None else monitored.name,
                "monitored_by_cycle": monitored_by_cycle,
                "active_cycles": active_cycles,
                "pending": pending,
            }
        # Gate state is read outside ``_lock``: the gate has its own condition
        # and the documented lock order is gate -> engine, never the inverse.
        view["admission"] = gate.state()
        # INV-LEASE-2: ``lease_snapshot()`` takes ``_lease_lock``, so it is
        # built *after* ``self._lock`` is released - the lease lock is never
        # acquired while the engine lock is held.
        view["leases"] = self.lease_snapshot()
        return view

    def fingerprint_state(self, now: float | None = None) -> dict[str, Any]:
        """Lock-protected copy of the per-fingerprint circuit registry."""

        with self._lock:
            moment = self._clock() if now is None else now
            cycle = self._cycles
            buckets = [
                bucket.to_dict(now=moment) for bucket in self._buckets.values()
            ]
        # Quarantined first, then ascending fingerprint: stable for assertions.
        buckets.sort(key=lambda item: (not item["quarantine_active"], item["fingerprint"]))
        return {
            "cycle": cycle,
            "now": moment,
            "quarantined_count": sum(
                1 for item in buckets if item["quarantine_active"]
            ),
            "fingerprints": buckets,
        }

    def sweep_stale_tasks(self, now: float | None = None) -> dict[str, Any]:
        """Detect and recover stale tasks; safe from the tick and the sweeper.

        Detection is :meth:`_evaluate_stale` (one ``STALE_TASK`` warning per
        breach). Recovery fires only once the age also exceeds the grace
        window, and it is the only writer of ``STALE_TASK_RECOVERED``:

        ``{"event": "STALE_TASK_RECOVERED", "task", "cycle", "age_sec",
        "timeout_sec", "grace_sec", "last_progress"}``

        The nested ``last_progress`` mapping is the brief's "last known
        progress line" citation, copied so later progress cannot rewrite it.
        A recovered task is ``FAILED`` and terminal, exactly like the
        exception path: this never retries and never touches the fingerprint
        circuit registry.
        """

        moment = self._clock() if now is None else now
        timeout = self._config.stale_timeout_sec
        grace = self._config.stale_recovery_grace_sec
        recovered = 0
        with self._lock:
            counts = self._evaluate_stale(moment)
            for task in list(self._curriculum.specs):
                if task.state not in STALE_ELIGIBLE:
                    continue
                age = progress_age_sec(task, moment)
                if age <= timeout + grace:
                    continue
                citation = {
                    "progress": task.progress,
                    "steps_required": task.steps_required,
                    "last_progress_at": task.last_progress_at,
                    "age_sec": age,
                }
                task.state = TaskState.FAILED
                task.pending_since = None
                task.last_progress_age_sec = age
                for slot, bound in list(self._monitored_by_cycle.items()):
                    if bound is task:
                        # Re-validate under the lock, like the step-commit path.
                        self._monitored_by_cycle.pop(slot, None)
                self._events.append(
                    {
                        "event": "STALE_TASK_RECOVERED",
                        "task": task.name,
                        "cycle": self._cycles,
                        "age_sec": age,
                        "timeout_sec": timeout,
                        "grace_sec": grace,
                        "last_progress": citation,
                    }
                )
                recovered += 1
        return {
            "now": moment,
            "scanned": counts["scanned"],
            "stale": counts["stale"],
            "recovered": recovered,
        }

    def _evaluate_stale(self, now: float) -> dict[str, int]:
        """Called with ``_lock`` held: refresh ages, emit ``STALE_TASK`` once.

        ``INV-STALE-1``: a task in ``STALE_ELIGIBLE`` must satisfy
        ``progress_age_sec(task, now) <= stale_timeout_sec``; a violation is
        logged as one ``STALE_TASK`` event. ``INV-STALE-2``: an unstamped task
        is stamped with ``now`` (the ``pending_since`` sentinel idiom), so an
        unobserved task never inherits the clock's epoch age.
        """

        timeout = self._config.stale_timeout_sec
        scanned = stale = 0
        for task in list(self._curriculum.specs):
            if task.state not in STALE_ELIGIBLE:
                continue
            scanned += 1
            if task.last_progress_at is None:
                task.last_progress_at = float(now)
            age = progress_age_sec(task, now)
            task.last_progress_age_sec = age
            if age <= timeout:
                continue
            if task.stale_since is None:
                task.stale_since = float(now)
                self._events.append(
                    {
                        "event": "STALE_TASK",
                        "task": task.name,
                        "cycle": self._cycles,
                        "age_sec": age,
                        "timeout_sec": timeout,
                    }
                )
            stale += 1
        return {"scanned": scanned, "stale": stale}

    def sweep_quarantine(self, now: float | None = None) -> dict[str, Any]:
        """Expire due quarantine timers, probe and close or re-arm each circuit."""

        moment = self._clock() if now is None else now
        candidates: list[tuple[str, int]] = []
        with self._lock:
            for fingerprint, bucket in self._buckets.items():
                if bucket.circuit_state is not FingerprintCircuitState.OPEN:
                    continue
                if bucket.quarantine_until is None or moment < bucket.quarantine_until:
                    continue
                bucket.circuit_state = FingerprintCircuitState.HALF_OPEN
                bucket.probe_epoch += 1
                bucket.last_seen = moment
                candidates.append((fingerprint, bucket.probe_epoch))

        probed = closed = reopened = 0
        for fingerprint, epoch in candidates:
            probed += 1
            try:
                ok = True if self._probe is None else bool(self._probe(fingerprint))
            except Exception:  # a raising probe is a failed probe
                ok = False
            with self._lock:
                bucket = self._buckets.get(fingerprint)
                if (
                    bucket is None
                    or bucket.circuit_state is not FingerprintCircuitState.HALF_OPEN
                    or bucket.probe_epoch != epoch
                ):
                    continue
                bucket.last_seen = moment
                if ok:
                    closed += 1
                    bucket.circuit_state = FingerprintCircuitState.CLOSED
                    bucket.consecutive_blocked = 0
                    bucket.quarantine_until = None
                    self._events.append(
                        {
                            "event": "FINGERPRINT_CLOSED",
                            "fingerprint": fingerprint,
                            "cycle": self._cycles,
                        }
                    )
                else:
                    reopened += 1
                    bucket.consecutive_blocked += 1
                    bucket.probe_epoch += 1
                    bucket.circuit_state = FingerprintCircuitState.OPEN
                    bucket.quarantine_until = (
                        moment + self._config.fingerprint_quarantine_sec
                    )
                    self._events.append(
                        {
                            "event": "FINGERPRINT_REOPENED",
                            "fingerprint": fingerprint,
                            "cycle": self._cycles,
                        }
                    )
        return {
            "now": moment,
            "probed": probed,
            "closed": closed,
            "reopened": reopened,
        }

    def start_scheduler(self, interval_sec: float | None = None) -> threading.Thread:
        """Start the daemon quarantine sweep thread (idempotent)."""

        with self._lock:
            existing = self._scheduler_thread
            if existing is not None and existing.is_alive():
                return existing
            interval = (
                self._config.quarantine_sweep_interval_sec
                if interval_sec is None
                else float(interval_sec)
            )
            self._scheduler_stop.clear()
            thread = threading.Thread(
                target=self._scheduler_loop,
                args=(interval,),
                name="maintainer-quarantine-sweep",
                daemon=True,
            )
            self._scheduler_thread = thread
        thread.start()
        return thread

    def stop_scheduler(self) -> None:
        """Stop the sweep thread and wait for it (idempotent)."""

        thread = self._scheduler_thread
        self._scheduler_stop.set()
        if thread is not None:
            thread.join(timeout=5.0)
        with self._lock:
            self._scheduler_thread = None
        self._scheduler_stop.clear()

    # ----------------------------------------------------------------- private

    def _monitor_step(self, task: TaskSpec) -> None:
        """Body of one monitor step; intentionally a no-op seam for tests."""

        return None

    def _pause(self, seconds: float) -> Any:
        """Inter-cycle wait: return ``True`` early when a wind-down is requested."""

        return self._stop.wait(seconds)

    def _interval(self, interval_sec: float | None) -> float:
        if interval_sec is None:
            return DEFAULT_CYCLE_INTERVAL_SEC
        if isinstance(interval_sec, bool) or not isinstance(interval_sec, (int, float)):
            raise ValueError("interval_sec must be a finite float >= 0")
        value = float(interval_sec)
        if not math.isfinite(value) or value < 0.0:
            raise ValueError("interval_sec must be a finite float >= 0")
        return value

    def _log_stop_reason(self) -> None:
        """Record and log why ``run_forever`` left its loop (REQ-04).

        Emits exactly one ``LOG.info`` line carrying the reason plus a
        ``STOP_HONOURED`` event, both before ``shutdown()`` runs, so the reason
        survives even when the wind-down later times out.
        """

        with self._lock:
            reason = self._stop_reason
            honoured = self._stop.is_set()
            cycle = self._cycles
        LOG.info(
            "maintainer engine leaving run_forever: reason=%s stop_requested=%s cycle=%d",
            reason,
            honoured,
            cycle,
        )
        with self._lock:
            self._events.append(
                {
                    "event": "STOP_HONOURED",
                    "cycle": cycle,
                    "reason": reason,
                    "stop_requested": honoured,
                }
            )

    def _cancel_timeout(self, timeout_sec: float | None) -> float:
        """Resolve the cancel wait bound (INV-CANCEL-2)."""

        if timeout_sec is None:
            return float(self._config.max_shutdown_wait_sec)
        if isinstance(timeout_sec, bool) or not isinstance(timeout_sec, (int, float)):
            raise ValueError("timeout_sec must be a finite float >= 0")
        value = float(timeout_sec)
        if not math.isfinite(value) or value < 0.0:
            raise ValueError("timeout_sec must be a finite float >= 0")
        return value

    def _runtime_path(self, name: str) -> Path | None:
        """``<state_root>/<name>``, or ``None`` when no root is configured."""

        root = self._state_root
        if root is None:
            return None
        return Path(root) / name

    def _write_runtime_markers(self) -> None:
        """Publish ``pid_file`` / ``heartbeat_file``; never raises (INV-MARK-1)."""

        pid_path = self.pid_file
        heartbeat_path = self.heartbeat_file
        if pid_path is None or heartbeat_path is None:
            return
        with self._lock:
            cycle = self._cycles
        try:
            # The marker root may not exist yet (fresh state root); create it
            # like the shutdown path does for task slots so a running engine
            # always publishes its markers rather than silently skipping them.
            pid_path.parent.mkdir(parents=True, exist_ok=True)
            _atomic_marker_write(pid_path, f"{os.getpid()}\n")
            _atomic_marker_write(
                heartbeat_path,
                json.dumps(
                    {"pid": os.getpid(), "cycle": cycle, "at": _wall_clock()}
                ),
            )
        except OSError:
            # Markers are advisory: a read-only root must not fail a cycle.
            return

    def _clear_runtime_markers(self) -> bool:
        """Remove both markers and their ``.tmp`` siblings (INV-MARK-3).

        Returns ``True`` when every unlink succeeded. A missing file is not an
        error; an ``OSError`` per file is reported via the return value so
        ``shutdown()`` can book it in ``failed_steps``.
        """

        ok = True
        for name in _RUNTIME_MARKER_NAMES:
            path = self._runtime_path(name)
            if path is None:
                continue
            try:
                path.unlink()
            except FileNotFoundError:
                continue
            except OSError:
                ok = False
        return ok

    def _prune_memory(self, memory: Any) -> tuple[dict[str, int], str | None]:
        """Trim the store to the configured maxima (INV-PRUNE-3, never raises)."""

        # A duck-typed store without ``prune`` has nothing to trim; that is not
        # corruption, so it stays silent (keeps DOD-16 of the consistency plan).
        if not callable(getattr(memory, "prune", None)):
            return {"runs": 0, "bug_fixes": 0, "learnings": 0}, None
        try:
            dropped = memory.prune(
                max_runs=self._config.memory_max_runs,
                max_bug_fixes=self._config.memory_max_bug_fixes,
                max_learnings=self._config.memory_max_learnings,
            )
        except Exception as error:  # pragma: no cover - defensive
            return (
                {"runs": 0, "bug_fixes": 0, "learnings": 0},
                f"MEMORY_CORRUPT: memory prune failed ({type(error).__name__})",
            )
        return {str(key): int(value) for key, value in dropped.items()}, None

    def _prune_stale_task_slots(self) -> list[str]:
        """Drop leftovers of slots whose task left the curriculum (REQ-03).

        Only ``_shutdown_state.json.tmp`` leftovers and empty directories are
        removed, so a committed ``_shutdown_state.json`` survives. Symlinked or
        out-of-root entries are refused; live curriculum names are never
        touched.
        """

        root = self._state_root
        if root is None:
            return []
        root_path = Path(root)
        with self._lock:
            known = {task.name for task in self._curriculum.specs}
        try:
            entries = sorted(root_path.iterdir())
        except OSError:
            return []
        pruned: list[str] = []
        for entry in entries:
            name = entry.name
            if name in known:
                continue
            try:
                if entry.is_symlink() or not entry.is_dir():
                    continue
                if entry.parent != root_path:
                    continue
                leftover = entry / SHUTDOWN_STATE_TMP
                try:
                    leftover.unlink()
                except FileNotFoundError:
                    pass
                except OSError:
                    continue
                try:
                    entry.rmdir()
                except OSError:
                    # A committed _shutdown_state.json keeps the slot alive.
                    pass
            except OSError:  # pragma: no cover - defensive
                continue
            with self._lock:
                self._active_roles.pop(name, None)
                self._events.append(
                    {
                        "event": "STALE_TASK_SLOT_PRUNED",
                        "cycle": self._cycles,
                        "task": name,
                    }
                )
            self.release_file_lock(name)
            pruned.append(name)
        return pruned

    def _resolve_role(self, task: TaskSpec) -> tuple[str | None, str]:
        """Role driving ``task``: observed value, or an explicit ``None``."""

        with self._lock:
            role = self._active_roles.get(task.name)
        if role is not None:
            return role, "engine"
        resolver = self._role_resolver
        if resolver is not None:
            resolved = resolver(task)
            if resolved is not None:
                return str(resolved), "resolver"
        spec_role = getattr(task, "role", None)
        if spec_role is not None:
            return str(spec_role), "spec"
        return None, "unobserved"

    def _scheduler_loop(self, interval: float) -> None:
        while not self._scheduler_stop.wait(interval):
            try:
                self.sweep_quarantine()
                self.sweep_stale_tasks()
            except Exception:  # pragma: no cover - defensive: never kill the thread
                continue

    def _record_cycle_outcome(
        self,
        outcome: str,
        task: TaskSpec,
        *,
        cycle: int,
        error: BaseException | None = None,
        now: float | None = None,
    ) -> None:
        """Tally one cycle outcome against the task's error fingerprint.

        Called with ``_lock`` held. ``outcome`` is ``BLOCKED`` (monitor step
        raised), ``COMPLETED`` or ``PROGRESS`` (a committed step clears the
        streak of that task's fingerprint).
        """

        moment = self._clock() if now is None else now
        if outcome == "BLOCKED":
            fingerprint = self._task_fingerprint.get(task.name)
            if error is not None:
                fingerprint = error_fingerprint(error)
                self._task_fingerprint[task.name] = fingerprint
            bucket = (
                None if fingerprint is None else self._bucket_for(fingerprint, moment)
            )
            self._events.append(
                {"event": "FAILED", "task": task.name, "cycle": cycle}
            )
            if bucket is None:
                return
            bucket.consecutive_blocked += 1
            bucket.last_cycle = cycle
            bucket.last_seen = moment
            if (
                bucket.consecutive_blocked
                >= self._config.fingerprint_quarantine_threshold
                and bucket.circuit_state is not FingerprintCircuitState.OPEN
            ):
                self._open_quarantine(bucket, now=moment, cycle=cycle)
            return

        fingerprint = self._task_fingerprint.pop(task.name, None)
        if fingerprint is not None:
            bucket = self._buckets.get(fingerprint)
            if bucket is not None and not bucket.is_quarantined(moment):
                bucket.consecutive_blocked = 0
                bucket.quarantine_until = None
                bucket.circuit_state = FingerprintCircuitState.CLOSED
                bucket.last_seen = moment
        if outcome == "COMPLETED":
            self._events.append(
                {"event": "COMPLETED", "task": task.name, "cycle": cycle}
            )

    def _open_quarantine(
        self, bucket: FingerprintBucket, *, now: float, cycle: int
    ) -> None:
        bucket.circuit_state = FingerprintCircuitState.OPEN
        bucket.quarantine_until = now + self._config.fingerprint_quarantine_sec
        bucket.probe_epoch += 1
        bucket.last_seen = now
        self._events.append(
            {
                "event": "FINGERPRINT_QUARANTINED",
                "fingerprint": bucket.fingerprint,
                "cycle": cycle,
            }
        )

    def _bucket_for(self, fingerprint: str, now: float) -> FingerprintBucket:
        """Return (creating if needed) the bucket of ``fingerprint``."""

        bucket = self._buckets.get(fingerprint)
        if bucket is None:
            bucket = FingerprintBucket(fingerprint=fingerprint, last_seen=now)
            self._buckets[fingerprint] = bucket
            self._enforce_bucket_cap()
        return bucket

    def _enforce_bucket_cap(self) -> None:
        """Bound the registry; ``OPEN``/``HALF_OPEN`` buckets are never evicted."""

        cap = self._config.max_fingerprint_buckets
        if len(self._buckets) <= cap:
            return
        evictable = sorted(
            (
                bucket
                for bucket in self._buckets.values()
                if bucket.circuit_state is FingerprintCircuitState.CLOSED
            ),
            key=lambda bucket: (bucket.last_seen, bucket.fingerprint),
        )
        for victim in evictable:
            if len(self._buckets) <= cap:
                break
            self._buckets.pop(victim.fingerprint, None)
            for name in [
                name
                for name, fingerprint in self._task_fingerprint.items()
                if fingerprint == victim.fingerprint
            ]:
                self._task_fingerprint.pop(name, None)

    def _blocked_backoff_sec(self, fingerprint: str) -> float:
        """Exponential backoff of one fingerprint's current streak, clamped."""

        bucket = self._buckets.get(fingerprint)
        if bucket is None:
            return 0.0
        streak = bucket.consecutive_blocked
        if streak == 0 or bucket.is_quarantined(self._clock()):
            # A quarantined fingerprint is skipped, not delayed.
            return 0.0
        return float(
            min(
                self._config.blocked_backoff_base_sec * (2 ** (streak - 1)),
                self._config.blocked_backoff_max_sec,
            )
        )

    def _is_fingerprint_quarantined(self, task: TaskSpec, now: float) -> bool:
        fingerprint = self._task_fingerprint.get(task.name)
        if fingerprint is None:
            return False
        bucket = self._buckets.get(fingerprint)
        return bucket is not None and bucket.is_quarantined(now)

    def _admit(self, task: TaskSpec) -> bool:
        """Signal-gate seam (REQ-03): may ``task`` be adopted/stepped this cycle?

        With no gate installed — the default — admissibility is unconditional
        and the engine behaves exactly as before this task existed. A gate is
        asked once per candidate per cycle; a rejection appends exactly one
        ``SIGNAL_REJECTED`` event carrying the gate's ``code`` and the engine
        moves on to the next candidate. A gate that raises is treated as a
        rejection (``GATE_ERROR``) rather than being allowed to kill the loop.
        """

        gate = self._signal_gate
        if gate is None:
            return True
        try:
            decision = gate(task.name)
        except Exception as error:
            self._events.append(
                {
                    "event": "SIGNAL_REJECTED",
                    "task": task.name,
                    "code": "GATE_ERROR",
                    "detail": type(error).__name__,
                    "cycle": self._cycles,
                }
            )
            return False
        if decision is None or getattr(decision, "accepted", False):
            return True
        self._events.append(
            {
                "event": "SIGNAL_REJECTED",
                "task": task.name,
                "code": getattr(decision, "code", "REJECTED"),
                "detail": getattr(decision, "detail", ""),
                "cycle": self._cycles,
            }
        )
        return False

    def _select_next_task(
        self,
        now: float,
        reported_skips: set[str] | None = None,
        *,
        peek_only: bool = False,
    ) -> TaskSpec | None:
        """Highest effective priority wins; ties break FIFO by insertion order.

        A task whose fingerprint is quarantined is neither adopted nor stepped;
        its ``FINGERPRINT_SKIPPED`` event is appended at most once per cycle.

        ``peek_only=True`` makes the call side-effect free (INV-CONC-6): no
        ``pending_since`` stamp and no event append. The admission peek in
        :meth:`_admission_difficulty` runs before the cycle snapshot, so it must
        not mutate anything the snapshot could fail to undo.
        """

        best: TaskSpec | None = None
        best_key: tuple[int, int] | None = None
        # A task already held by a live cycle slot is not offered to another
        # cycle: two overlapping cycles stepping one task would double-count
        # its progress and emit two COMPLETED outcomes for it (REQ-05).
        held = {id(task) for task in self._monitored_by_cycle.values()}
        for index, task in enumerate(self._curriculum.specs):
            if task.state not in NON_TERMINAL:
                continue
            if id(task) in held:
                continue
            if reported_skips is not None and task.name in reported_skips:
                # Signal-gate rejection: reported once, then skipped for the
                # rest of the cycle — this is what terminates an all-rejected
                # cycle instead of spinning on the same candidate.
                continue
            if self._is_fingerprint_quarantined(task, now):
                fingerprint = self._task_fingerprint[task.name]
                if (
                    not peek_only
                    and reported_skips is not None
                    and fingerprint not in reported_skips
                ):
                    reported_skips.add(fingerprint)
                    self._events.append(
                        {
                            "event": "FINGERPRINT_SKIPPED",
                            "task": task.name,
                            "fingerprint": fingerprint,
                            "cycle": self._cycles,
                        }
                    )
                continue
            if not peek_only:
                stamp_pending_since(task, now)
            level = effective_priority(
                task, cycle=self._cycles, now=now, config=self._config
            ).value
            key = (level, -index)
            if best_key is None or key > best_key:
                best, best_key = task, key
        return best

    def _should_preempt(
        self, incoming: TaskSpec, monitored: TaskSpec, now: float
    ) -> bool:
        if not self._config.priority_preemption_enabled:
            return False
        if monitored.state not in NON_TERMINAL:
            return False
        stamp_pending_since(incoming, now)
        stamp_pending_since(monitored, now)
        incoming_level = effective_priority(
            incoming, cycle=self._cycles, now=now, config=self._config
        ).value
        monitored_level = effective_priority(
            monitored, cycle=self._cycles, now=now, config=self._config
        ).value
        return incoming_level > monitored_level

    def _preempt(
        self, incoming: TaskSpec, monitored: TaskSpec, *, cycle: int
    ) -> None:
        """Return ``monitored`` to ``PENDING`` keeping progress and aging clock."""

        monitored.state = TaskState.PENDING
        monitored.preempted_count += 1
        self._monitored_by_cycle.pop(cycle, None)
        self._events.append(
            {"event": "PREEMPTED", "task": monitored.name, "cycle": cycle}
        )

    def _adopt(self, incoming: TaskSpec, *, cycle: int | None = None) -> None:
        """Bind ``incoming`` to a cycle's monitored slot.

        ``cycle`` defaults to the legacy single-cycle slot (``_cycles``) so the
        historical call shape still works.
        """

        self._monitored_by_cycle[self._cycles if cycle is None else cycle] = incoming
        incoming.state = TaskState.IN_PROGRESS
        # A freshly adopted task starts fresh: it must not inherit the age of
        # an observation from before the preemption (INV-STALE-5).
        stamp_progress(incoming, self._clock())

    @property
    def _monitored(self) -> TaskSpec | None:
        """Legacy single-cycle view over :attr:`_monitored_by_cycle`.

        Ambiguity is reported honestly: exactly one distinct task across all
        live cycle slots is *the* monitored task, several distinct tasks are
        not collapsible and yield ``None``. Read-only on purpose - every write
        path is cycle-scoped, so there is no correct global assignment.

        While no cycle is running, the slot carried over from the last
        single-cycle run is reported as long as the task is still in flight, so
        a caller that runs one cycle at a time keeps the historical view (the
        monitored task survives between cycles) and drops it once the task is
        completed or fails.
        """

        seen: dict[int, TaskSpec] = {}
        for task in self._monitored_by_cycle.values():
            seen[id(task)] = task
        carried = self._carried_monitored
        if (
            not seen
            and self._active_cycles == 0
            and carried is not None
            and carried.state in NON_TERMINAL
        ):
            seen[id(carried)] = carried
        if len(seen) == 1:
            return next(iter(seen.values()))
        return None
