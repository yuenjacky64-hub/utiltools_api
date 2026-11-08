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

import threading
import time
from collections import deque
from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.tasks.curriculum import (
    NON_TERMINAL,
    STALE_ELIGIBLE,
    TaskCurriculum,
    TaskSpec,
    TaskState,
    effective_priority,
    progress_age_sec,
    stamp_pending_since,
    stamp_progress,
)

__all__ = [
    "CycleResult",
    "FingerprintBucket",
    "FingerprintCircuitState",
    "MaintainerEngine",
    "error_fingerprint",
]

EVENT_LOG_MAXLEN = 100


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

    def to_dict(self) -> dict:
        return {
            "cycle": self.cycle,
            "monitored": self.monitored,
            "priority": self.priority,
            "preempted": self.preempted,
            "steps": self.steps,
        }


class MaintainerEngine:
    """Runs maintainer cycles, preempting lower-priority monitoring."""

    def __init__(
        self,
        curriculum: TaskCurriculum,
        config: MaintainerConfig | None = None,
        clock: Callable[[], float] = time.monotonic,
        probe: Callable[[str], bool] | None = None,
        signal_gate: Callable[[str], Any] | None = None,
    ) -> None:
        self._curriculum = curriculum
        self._config = config if config is not None else curriculum.config
        self._clock = clock
        self._lock = threading.RLock()
        self._monitored: TaskSpec | None = None
        self._events: deque[dict[str, Any]] = deque(maxlen=EVENT_LOG_MAXLEN)
        self._cycles = 0
        self._buckets: dict[str, FingerprintBucket] = {}
        self._task_fingerprint: dict[str, str] = {}
        self._probe = probe
        self._signal_gate = signal_gate
        self._scheduler_thread: threading.Thread | None = None
        self._scheduler_stop = threading.Event()

    # ------------------------------------------------------------------ public

    @property
    def lock(self) -> threading.RLock:
        return self._lock

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

    def run_cycle(self) -> CycleResult:
        """Execute at most ``max_steps_per_cycle`` monitor steps."""

        steps = 0
        preempted_name: str | None = None
        monitored_name: str | None = None
        priority_name: str | None = None
        reported_skips: set[str] = set()

        with self._lock:
            self._cycles += 1
            cycle = self._cycles
            # Poll-tick half of the stale-task state machine: refresh every
            # active task's progress age and warn (``STALE_TASK``) on a
            # breach. Recovery is the sweep thread's job, never the tick's.
            self._evaluate_stale(self._clock())

        while steps < self._config.max_steps_per_cycle:
            with self._lock:
                now = self._clock()
                if self._monitored is not None and (
                    self._monitored.state not in NON_TERMINAL
                ):
                    self._monitored = None
                incoming = self._select_next_task(now, reported_skips)
                if self._monitored is None:
                    if incoming is None:
                        break
                    if not self._admit(incoming):
                        reported_skips.add(incoming.name)
                        continue
                    self._adopt(incoming)
                elif (
                    incoming is not None
                    and incoming is not self._monitored
                    and self._should_preempt(incoming, self._monitored, now)
                ):
                    if not self._admit(incoming):
                        reported_skips.add(incoming.name)
                        continue
                    previous = self._monitored
                    self._preempt(incoming, previous, cycle=cycle)
                    preempted_name = previous.name
                    self._adopt(incoming)
                task = self._monitored
                if task is None:
                    break
                monitored_name = task.name
                priority_name = effective_priority(
                    task, cycle=cycle, now=now, config=self._config
                ).name
                task.state = TaskState.RUNNING
                next_progress = task.progress + 1

            # Monitor step body runs outside the lock.
            error: BaseException | None = None
            try:
                self._monitor_step(task)
            except Exception as exc:  # pragma: no cover - defensive path
                error = exc

            with self._lock:
                if self._monitored is not task:
                    # A concurrent cycle preempted or retired it: discard.
                    steps += 1
                    continue
                if error is not None:
                    task.state = TaskState.FAILED
                    task.pending_since = None
                    self._monitored = None
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
                    self._monitored = None
                    self._record_cycle_outcome("COMPLETED", task, cycle=cycle)
                else:
                    self._record_cycle_outcome("PROGRESS", task, cycle=cycle)
            steps += 1

        return CycleResult(
            cycle=cycle,
            monitored=monitored_name,
            priority=priority_name,
            preempted=preempted_name,
            steps=steps,
        )

    def snapshot(self) -> dict:
        """Lock-protected view of the monitored task and pending priorities."""

        with self._lock:
            now = self._clock()
            monitored = self._monitored
            pending: dict[str, str] = {}
            for task in self._curriculum.specs:
                if task.state not in NON_TERMINAL:
                    continue
                if monitored is not None and task is monitored:
                    continue
                stamp_pending_since(task, now)
                pending[task.name] = effective_priority(
                    task, cycle=self._cycles, now=now, config=self._config
                ).name
            return {
                "monitored": None if monitored is None else monitored.name,
                "pending": pending,
            }

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
                if self._monitored is task:
                    # Re-validate under the lock, like the step-commit path.
                    self._monitored = None
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
        self, now: float, reported_skips: set[str] | None = None
    ) -> TaskSpec | None:
        """Highest effective priority wins; ties break FIFO by insertion order.

        A task whose fingerprint is quarantined is neither adopted nor stepped;
        its ``FINGERPRINT_SKIPPED`` event is appended at most once per cycle.
        """

        best: TaskSpec | None = None
        best_key: tuple[int, int] | None = None
        for index, task in enumerate(self._curriculum.specs):
            if task.state not in NON_TERMINAL:
                continue
            if reported_skips is not None and task.name in reported_skips:
                # Signal-gate rejection: reported once, then skipped for the
                # rest of the cycle — this is what terminates an all-rejected
                # cycle instead of spinning on the same candidate.
                continue
            if self._is_fingerprint_quarantined(task, now):
                fingerprint = self._task_fingerprint[task.name]
                if reported_skips is not None and fingerprint not in reported_skips:
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
        self._monitored = None
        self._events.append(
            {"event": "PREEMPTED", "task": monitored.name, "cycle": cycle}
        )

    def _adopt(self, incoming: TaskSpec) -> None:
        self._monitored = incoming
        incoming.state = TaskState.IN_PROGRESS
        # A freshly adopted task starts fresh: it must not inherit the age of
        # an observation from before the preemption (INV-STALE-5).
        stamp_progress(incoming, self._clock())
