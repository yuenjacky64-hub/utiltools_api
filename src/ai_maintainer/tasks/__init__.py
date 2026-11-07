"""Task lifecycle primitives (specs, priority, curriculum)."""

from ai_maintainer.tasks.curriculum import (
    BASE_PRIORITY_BY_DIFFICULTY,
    NON_TERMINAL,
    Difficulty,
    Priority,
    TaskCurriculum,
    TaskSpec,
    TaskState,
    effective_priority,
)

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
