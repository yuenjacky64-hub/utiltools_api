"""Priority-ordered maintainer cycle with preemption and aging.

One ``threading.RLock`` guards every read and write of the monitored task, the
task priority bookkeeping and the event deque. Monitor step bodies run outside
that lock and their result is committed only after re-validating that the
stepped task is still the monitored one, so a concurrent preemption can never
double-count progress.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from dataclasses import dataclass
from typing import Any, Callable

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.tasks.curriculum import (
    NON_TERMINAL,
    TaskCurriculum,
    TaskSpec,
    TaskState,
    effective_priority,
)

__all__ = ["CycleResult", "MaintainerEngine"]

EVENT_LOG_MAXLEN = 100


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
    ) -> None:
        self._curriculum = curriculum
        self._config = config if config is not None else curriculum.config
        self._clock = clock
        self._lock = threading.RLock()
        self._monitored: TaskSpec | None = None
        self._events: deque[dict[str, Any]] = deque(maxlen=EVENT_LOG_MAXLEN)
        self._cycles = 0

    # ------------------------------------------------------------------ public

    @property
    def lock(self) -> threading.RLock:
        return self._lock

    @property
    def events(self) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._events)

    def run_cycle(self) -> CycleResult:
        """Execute at most ``max_steps_per_cycle`` monitor steps."""

        steps = 0
        preempted_name: str | None = None
        monitored_name: str | None = None
        priority_name: str | None = None

        with self._lock:
            self._cycles += 1
            cycle = self._cycles

        while steps < self._config.max_steps_per_cycle:
            with self._lock:
                now = self._clock()
                if self._monitored is not None and (
                    self._monitored.state not in NON_TERMINAL
                ):
                    self._monitored = None
                incoming = self._select_next_task(now)
                if self._monitored is None:
                    if incoming is None:
                        break
                    self._adopt(incoming)
                elif (
                    incoming is not None
                    and incoming is not self._monitored
                    and self._should_preempt(incoming, self._monitored, now)
                ):
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
                    task.pending_since = 0.0
                    self._monitored = None
                    self._events.append(
                        {"event": "FAILED", "task": task.name, "cycle": cycle}
                    )
                    steps += 1
                    continue
                task.progress = max(task.progress, next_progress)
                if task.progress >= task.steps_required:
                    task.state = TaskState.COMPLETED
                    task.pending_since = 0.0
                    self._monitored = None
                    self._events.append(
                        {"event": "COMPLETED", "task": task.name, "cycle": cycle}
                    )
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
                pending[task.name] = effective_priority(
                    task, cycle=self._cycles, now=now, config=self._config
                ).name
            return {
                "monitored": None if monitored is None else monitored.name,
                "pending": pending,
            }

    # ----------------------------------------------------------------- private

    def _monitor_step(self, task: TaskSpec) -> None:
        """Body of one monitor step; intentionally a no-op seam for tests."""

        return None

    def _select_next_task(self, now: float) -> TaskSpec | None:
        """Highest effective priority wins; ties break FIFO by insertion order."""

        best: TaskSpec | None = None
        best_key: tuple[int, int] | None = None
        for index, task in enumerate(self._curriculum.specs):
            if task.state not in NON_TERMINAL:
                continue
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
