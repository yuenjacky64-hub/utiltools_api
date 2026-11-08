"""Append-only audit journal for orchestrator state repairs and signal refusals.

The Python maintainer has no audit trail of its own: :attr:`MaintainerEngine.events`
is a bounded in-memory ``deque`` that forgets as soon as the process exits. A
cross-store repair, however, rewrites runtime state that an operator later has
to explain, so it needs the same guarantee the Java house pattern gives
(``com.example.utiltools.audit``): journal *then* execute, one operation type
constant per journalled operation, and a pre-image on the line.

Two rules make the journal audit-safe:

``INV-AUDIT-1`` (journal-before-write)
    The ``PENDING`` line for an operation is appended, flushed and
    ``os.fsync``-ed *before* the first byte of any store is written. A crash
    between the two lines leaves an orphan ``PENDING`` — never an unjournaled
    mutation — and the pre-image on that line makes the write replayable.

``INV-AUDIT-2`` (stable entry id)
    The outcome line (``SUCCEEDED`` / ``FAILED`` / ``REFUSED``) reuses the
    ``entry_id`` of its ``PENDING`` line, so a torn pair is detectable by
    scanning the file for ids whose last phase is ``PENDING``.

The file is JSONL (one object per line, ``ensure_ascii=False``) and never
rewritten: readers tolerate a missing file and skip malformed lines, exactly
like the tolerant-read doctrine in :mod:`ai_maintainer.checks`.
"""

from __future__ import annotations

import json
import os
import time
import uuid
from dataclasses import dataclass, field, replace
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Iterable

__all__ = [
    "PHASE_FAILED",
    "PHASE_PENDING",
    "PHASE_REFUSED",
    "PHASE_SUCCEEDED",
    "AuditEntry",
    "AuditedOperations",
    "OperationType",
    "read_journal",
]

PHASE_PENDING = "PENDING"
PHASE_SUCCEEDED = "SUCCEEDED"
PHASE_FAILED = "FAILED"
PHASE_REFUSED = "REFUSED"


class OperationType(str, Enum):
    """Journalled operation kinds; the value is the line's type discriminator."""

    CONSISTENCY_RECOVERY = "CONSISTENCY_RECOVERY"
    SIGNAL_REJECTED = "SIGNAL_REJECTED"


@dataclass(frozen=True)
class AuditEntry:
    """One journalled operation, as it is serialised on a single line."""

    entry_id: str
    op: OperationType
    task: str
    store: str
    action: str
    phase: str
    before: dict[str, Any] = field(default_factory=dict)
    after: dict[str, Any] = field(default_factory=dict)
    recorded_at: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        """JSON-serialisable view; ``op``/``phase`` stay plain strings."""

        return {
            "entry_id": self.entry_id,
            "op": self.op.value,
            "task": self.task,
            "store": self.store,
            "action": self.action,
            "phase": self.phase,
            "before": dict(self.before),
            "after": dict(self.after),
            "recorded_at": self.recorded_at,
        }

    def advanced(self, phase: str, after: dict[str, Any] | None = None) -> "AuditEntry":
        """Outcome line for this entry: same ``entry_id``, new phase/after-image."""

        return replace(self, phase=phase, after=dict(after or {}))


class AuditedOperations:
    """Append-only journal writer; every mutation path must go through it."""

    def __init__(
        self,
        journal_path: str | Path | None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._journal_path = Path(journal_path) if journal_path is not None else None
        self._clock = clock

    @property
    def journal_path(self) -> Path | None:
        return self._journal_path

    def new_entry(
        self,
        op: OperationType,
        *,
        task: str,
        store: str,
        action: str,
        before: dict[str, Any] | None = None,
    ) -> AuditEntry:
        """Build a ``PENDING`` entry stamped with the injected clock."""

        return AuditEntry(
            entry_id=uuid.uuid4().hex,
            op=op,
            task=task,
            store=store,
            action=action,
            phase=PHASE_PENDING,
            before=dict(before or {}),
            after={},
            recorded_at=self._clock(),
        )

    def record(self, entry: AuditEntry) -> AuditEntry:
        """Append ``entry`` (normally ``PENDING``) and return it."""

        self._append(entry)
        return entry

    def complete(
        self,
        entry: AuditEntry,
        phase: str,
        after: dict[str, Any] | None = None,
        *,
        at: float | None = None,
    ) -> AuditEntry:
        """Append the outcome line of ``entry`` with the same ``entry_id``."""

        outcome = replace(
            entry,
            phase=phase,
            after=dict(after or {}),
            recorded_at=self._clock() if at is None else at,
        )
        self._append(outcome)
        return outcome

    def read(self) -> list[dict[str, Any]]:
        """Every readable line of this journal (tolerant, in file order)."""

        if self._journal_path is None:
            return []
        return read_journal(self._journal_path)

    def _append(self, entry: AuditEntry) -> None:
        path = self._journal_path
        if path is None:
            return
        blob = json.dumps(entry.to_dict(), ensure_ascii=False)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as handle:
            handle.write(blob + "\n")
            handle.flush()
            os.fsync(handle.fileno())


def read_journal(path: str | Path) -> list[dict[str, Any]]:
    """Read a JSONL journal, skipping malformed lines; missing file is empty."""

    journal = Path(path)
    try:
        text = journal.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    entries: list[dict[str, Any]] = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            parsed = json.loads(line)
        except ValueError:
            continue
        if isinstance(parsed, dict):
            entries.append(parsed)
    return entries


def entries_by_id(entries: Iterable[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """Group journal lines by ``entry_id``, preserving file order."""

    grouped: dict[str, list[dict[str, Any]]] = {}
    for entry in entries:
        entry_id = entry.get("entry_id")
        if isinstance(entry_id, str):
            grouped.setdefault(entry_id, []).append(entry)
    return grouped
