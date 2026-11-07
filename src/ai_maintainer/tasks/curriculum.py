"""Task specs, priority ranking and the task curriculum.

Design note: a task's ``priority`` is stored once as the *base* rank (derived
from difficulty unless supplied explicitly). Boosts are never written back to
that field; :func:`effective_priority` is a pure function of
``(base, cycles_since_proposed, pending_seconds, config)``, so boosts are
idempotent and the serialized JSON stays deterministic.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field, fields
from enum import Enum, IntEnum
from typing import Any, Callable

from ai_maintainer.config import MaintainerConfig

__all__ = [
    "BASE_PRIORITY_BY_DIFFICULTY",
    "NON_TERMINAL",
    "Difficulty",
    "Priority",
    "TaskCurriculum",
    "TaskSpec",
    "TaskState",
    "effective_priority",
]

PRIORITY_NAMES = "HIGH, NORMAL, LOW"


def _priority_error(value: Any) -> ValueError:
    return ValueError(f"priority must be one of {PRIORITY_NAMES}; got {value!r}")


class Priority(IntEnum):
    """Ordered task priority: ``LOW < NORMAL < HIGH``."""

    LOW = 0
    NORMAL = 1
    HIGH = 2

    @classmethod
    def parse(cls, value: Any) -> "Priority":
        """Accept a ``Priority``, a name (case-insensitive) or an int level."""

        if isinstance(value, cls):
            return value
        if isinstance(value, bool):
            raise _priority_error(value)
        if isinstance(value, int):
            try:
                return cls(value)
            except ValueError:
                raise _priority_error(value) from None
        if isinstance(value, str):
            try:
                return cls[value.strip().upper()]
            except KeyError:
                raise _priority_error(value) from None
        raise _priority_error(value)

    def boost(self, steps: int = 1) -> "Priority":
        """Raise the priority by ``steps`` ranks, clamped to ``HIGH``."""

        level = self.value + int(steps)
        return Priority(max(Priority.LOW.value, min(level, Priority.HIGH.value)))


class Difficulty(IntEnum):
    """Ordered difficulty; drives the base priority."""

    EASY = 0
    MEDIUM = 1
    HARD = 2
    COMPLEX = 3

    @classmethod
    def parse(cls, value: Any) -> "Difficulty":
        if isinstance(value, cls):
            return value
        if isinstance(value, int) and not isinstance(value, bool):
            try:
                return cls(value)
            except ValueError:
                raise ValueError(f"unknown difficulty: {value!r}") from None
        if isinstance(value, str):
            try:
                return cls[value.strip().upper()]
            except KeyError:
                raise ValueError(f"unknown difficulty: {value!r}") from None
        raise ValueError(f"unknown difficulty: {value!r}")


class TaskState(Enum):
    """Lifecycle state of a task spec."""

    PENDING = "PENDING"
    IN_PROGRESS = "IN_PROGRESS"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

    @classmethod
    def parse(cls, value: Any) -> "TaskState":
        if isinstance(value, cls):
            return value
        if isinstance(value, str):
            try:
                return cls[value.strip().upper()]
            except KeyError:
                raise ValueError(f"unknown task state: {value!r}") from None
        raise ValueError(f"unknown task state: {value!r}")


NON_TERMINAL = frozenset(
    {TaskState.PENDING, TaskState.IN_PROGRESS, TaskState.RUNNING}
)

BASE_PRIORITY_BY_DIFFICULTY = {
    Difficulty.COMPLEX: Priority.HIGH,
    Difficulty.HARD: Priority.NORMAL,
    Difficulty.MEDIUM: Priority.LOW,
    Difficulty.EASY: Priority.LOW,
}


@dataclass
class TaskSpec:
    """A maintainer task carrying its base priority and lifecycle state."""

    name: str
    difficulty: Difficulty = Difficulty.EASY
    priority: Priority | None = None
    state: TaskState = TaskState.PENDING
    progress: int = 0
    steps_required: int = 1
    last_proposed_cycle: int | None = None
    pending_since: float = 0.0
    preempted_count: int = 0

    def __post_init__(self) -> None:
        if not isinstance(self.name, str) or not self.name.strip():
            raise ValueError("task name must be a non-empty string")
        self.difficulty = Difficulty.parse(self.difficulty)
        if self.priority is None:
            self.priority = BASE_PRIORITY_BY_DIFFICULTY[self.difficulty]
        else:
            self.priority = Priority.parse(self.priority)
        self.state = TaskState.parse(self.state)
        if not isinstance(self.progress, int) or self.progress < 0:
            raise ValueError("progress must be an int >= 0")
        if not isinstance(self.steps_required, int) or self.steps_required < 1:
            raise ValueError("steps_required must be an int >= 1")

    def to_dict(self) -> dict:
        """Serialize to a JSON-ready mapping (priority as its name)."""

        return {
            "name": self.name,
            "difficulty": self.difficulty.name,
            "priority": self.priority.name,
            "state": self.state.value,
            "progress": self.progress,
            "steps_required": self.steps_required,
            "last_proposed_cycle": self.last_proposed_cycle,
            "pending_since": self.pending_since,
            "preempted_count": self.preempted_count,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "TaskSpec":
        """Rebuild a spec from :meth:`to_dict` output; rejects bad priorities."""

        if not isinstance(data, dict):
            raise ValueError("task spec data must be a mapping")
        known = {item.name for item in fields(cls)}
        unknown = sorted(set(data) - known)
        if unknown:
            raise ValueError(f"unknown task spec field(s): {', '.join(unknown)}")
        return cls(**data)


def _pending_seconds(task: TaskSpec, now: float) -> float:
    """Seconds the task has spent in a non-terminal state (never negative)."""

    if task.state not in NON_TERMINAL:
        return 0.0
    return max(0.0, now - task.pending_since)


def _recency_triggered(task: TaskSpec, cycle: int, config: MaintainerConfig) -> bool:
    if task.last_proposed_cycle is None:
        return True
    return (cycle - task.last_proposed_cycle) >= config.recency_boost_cycles


def _aging_triggered(task: TaskSpec, now: float, config: MaintainerConfig) -> bool:
    return _pending_seconds(task, now) > config.aging_threshold_seconds


def effective_priority(
    task: TaskSpec,
    *,
    cycle: int,
    now: float,
    config: MaintainerConfig,
) -> Priority:
    """Base priority plus one rank per triggered boost, clamped to ``HIGH``.

    Boosts are recomputed from scratch on every call (never stored), so the
    function is monotone non-decreasing in ``now`` and ``cycle`` (INV-4).
    """

    steps = int(_recency_triggered(task, cycle, config)) + int(
        _aging_triggered(task, now, config)
    )
    return task.priority.boost(steps)


@dataclass
class TaskCurriculum:
    """Ordered pool of task specs plus the recency bookkeeping."""

    specs: list[TaskSpec] = field(default_factory=list)
    config: MaintainerConfig = field(default_factory=MaintainerConfig)
    clock: Callable[[], float] = time.monotonic
    _cycle_count: int = field(default=0, init=False, repr=False)

    def __post_init__(self) -> None:
        now = self.clock()
        for task in self.specs:
            if task.state in NON_TERMINAL and task.pending_since == 0.0:
                task.pending_since = now

    @property
    def cycle(self) -> int:
        """Number of :meth:`generate_task` calls so far."""

        return self._cycle_count

    def __getitem__(self, name: str) -> TaskSpec:
        for task in self.specs:
            if task.name == name:
                return task
        raise ValueError(f"unknown task: {name!r}")

    def generate_task(self) -> TaskSpec | None:
        """Propose the highest effective-priority non-terminal task.

        Ties break by least-recently-proposed, then curriculum order; the
        winner's ``last_proposed_cycle`` is stamped so the recency boost is
        idempotent per proposal.
        """

        self._cycle_count += 1
        now = self.clock()
        candidates = [
            (index, task)
            for index, task in enumerate(self.specs)
            if task.state in NON_TERMINAL
        ]
        if not candidates:
            return None

        def rank(item: tuple[int, TaskSpec]) -> tuple[int, int, int]:
            index, task = item
            effective = effective_priority(
                task, cycle=self._cycle_count, now=now, config=self.config
            )
            last = task.last_proposed_cycle
            last_key = -1 if last is None else last
            return (effective.value, -last_key, -index)

        _, winner = max(candidates, key=rank)
        winner.last_proposed_cycle = self._cycle_count
        return winner

    def apply_state(
        self,
        name: str,
        state: TaskState,
        now: float | None = None,
    ) -> TaskSpec:
        """Drive one task into ``state``, stamping/clearing the aging clock."""

        task = self[name]
        new_state = TaskState.parse(state)
        moment = self.clock() if now is None else now
        if new_state in NON_TERMINAL:
            if task.state not in NON_TERMINAL or task.pending_since == 0.0:
                task.pending_since = moment
        else:
            task.pending_since = 0.0
        task.state = new_state
        return task
