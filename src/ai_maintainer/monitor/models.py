"""Snapshot model for the monitor watcher: active tasks and parked status.

The classification rule lives here and nowhere else. :func:`is_parked_status`
is the single place that knows the parked prefix, and :attr:`ActiveTask.parked`
is its only in-tree caller: the watcher's ``is_task_parked`` scan delegates
through the property and never re-derives the comparison.

Both dataclasses are frozen, so a caller holding a snapshot cannot mutate it
from under another reader, and ``active_tasks`` defaults to an immutable empty
tuple instead of a shared mutable list.
"""

from __future__ import annotations

from dataclasses import dataclass

PARKED_STATUS_PREFIX = "Awaiting"


def is_parked_status(status: str | None) -> bool:
    """Return ``True`` when ``status`` starts with the parked prefix.

    Case-sensitive and prefix-only, as the brief words it: ``'Awaiting Merge'``
    and ``'Awaiting'`` are parked, while ``'awaiting merge'`` is not. A status
    that is not a string (``None``, an int, ...) degrades to ``False`` rather
    than raising from ``startswith``.
    """

    if not isinstance(status, str):
        return False
    return bool(status.startswith(PARKED_STATUS_PREFIX))


@dataclass(frozen=True)
class ActiveTask:
    """One task as reported by the orchestrator: an id and a status string."""

    task_id: str
    status: str = ""

    @property
    def parked(self) -> bool:
        """Whether :attr:`status` is a parked status (delegates to the rule)."""

        return is_parked_status(self.status)


@dataclass(frozen=True)
class FrameworkSnapshot:
    """Immutable view of the orchestrator's active tasks at one moment."""

    active_tasks: tuple[ActiveTask, ...] = ()
