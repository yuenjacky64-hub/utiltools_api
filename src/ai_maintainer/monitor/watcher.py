"""Read-only query surface over one :class:`FrameworkSnapshot`.

The watcher is a pure view: it is handed a snapshot and answers questions about
it. It never reads ``_signal.txt``, ``active_task`` or any other on-disk state,
so an answer is deterministic in tests and cannot drift from the snapshot the
caller is looking at.
"""

from __future__ import annotations

from ai_maintainer.monitor.models import FrameworkSnapshot


class FrameworkWatcher:
    """Answers questions about an injected snapshot; performs no I/O."""

    def __init__(self, snapshot: FrameworkSnapshot | None = None) -> None:
        self._snapshot = FrameworkSnapshot() if snapshot is None else snapshot

    def get_snapshot(self) -> FrameworkSnapshot:
        """Return the snapshot this watcher was built with (never mutated)."""

        return self._snapshot

    def is_task_parked(self, task_id: str) -> bool:
        """Whether the named task currently holds a parked status.

        Walks ``FrameworkSnapshot.active_tasks`` in declared order and returns
        the matched task's :attr:`~ai_maintainer.monitor.models.ActiveTask.parked`
        property, so first match wins on duplicate ids. An unknown, empty or
        non-string ``task_id`` degrades to ``False`` instead of raising.
        """

        if not isinstance(task_id, str) or not task_id:
            return False
        for task in self.get_snapshot().active_tasks:
            if task.task_id == task_id:
                return bool(task.parked)
        return False
