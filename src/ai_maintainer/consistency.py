"""Cross-store task state consistency and audit-safe repair.

The orchestrator keeps one task's state in several stores: the *signal* store
(``<state_root>/<task>/_signal.txt``), the append-only *progress* log
(``task_progress.txt`` / ``agent_progress.log``), the *timeline* journal
(``timeline.jsonl``) and the terminal *archive* trees (``hist_tasks`` /
``dead_tasks``). When a role dies mid-signal — the sigterm case this task
exists for — those stores disagree: a task is archived as completed while its
``_signal.txt`` still says ``Running 1``.

The invariant is ``INV-CON-2``: a task is consistent iff its signal state is
absent *or* of the same state group as the canonical state derived from the
higher-authority stores. Exactly one store is legitimately writable by the
framework, so repair always rewrites ``_signal.txt`` and never a log or an
archive (see the module-level ``INV-*`` notes below).

``INV-CON-1`` (authority order)
    Terminal evidence is irreversible and outranks everything, and the archive
    outranks the signal because the retire path archives only after a role is
    done: archive, then a terminal signal, then ``timeline.jsonl``, then the
    progress log, then an active signal.

``INV-CON-2`` (agreement)
    Consistent iff the signal is absent, or absent-state, or
    ``group(signal) == group(canonical)``. A task directory without a
    ``_signal.txt`` is a queued slot and is never rewritten.

``INV-CON-3`` (repair target)
    Repair writes ``token(canonical)`` into ``_signal.txt``. It cannot change
    ``canonical`` (that came from a higher-or-equal authority), which makes the
    repair idempotent by construction (``INV-REC-2``): the next scan is a no-op.

``INV-REC-1`` (single writer)
    One ``threading.RLock`` serialises classify -> journal -> write, so the
    startup job, the report route and a second process cannot interleave.

``INV-REC-2`` (idempotence)
    A second :meth:`ConsistencyService.recover_all` on repaired stores reports
    ``recovered: 0``.

Every repair is journalled *before* the store is touched (``INV-AUDIT-1`` in
:mod:`ai_maintainer.audit`). The report route is strictly read-only.
"""

from __future__ import annotations

import json
import os
import threading
import time
from collections import deque
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable, Mapping

from ai_maintainer import checks
from ai_maintainer.audit import (
    PHASE_FAILED,
    PHASE_REFUSED,
    PHASE_SUCCEEDED,
    AuditedOperations,
    OperationType,
)
from ai_maintainer.tasks.curriculum import TaskState

__all__ = [
    "ACTION_ALIGN_SIGNAL_TO_PROGRESS",
    "ACTION_ALIGN_TO_ARCHIVE",
    "ACTION_NOOP",
    "ACTION_REFUSED_MANUAL_OVERRIDE",
    "ARCHIVE_DIRS",
    "CLASS_CONSISTENT",
    "CLASS_PROGRESS_SIGNAL_DRIFT",
    "CLASS_TERMINAL_ARCHIVE_DRIFT",
    "MANUAL_OVERRIDE_FILE",
    "PROGRESS_FILES",
    "REPORT_STORES",
    "SIGNAL_FILE",
    "STATUS_CONSISTENT",
    "STATUS_INCONSISTENT",
    "TIMELINE_FILE",
    "UnknownTaskError",
    "ConsistencyService",
    "StoreReading",
    "StoreRoots",
    "TaskStoreSnapshot",
    "resolve_roots",
    "run_startup_recovery",
    "state_for_token",
]

SIGNAL_FILE = checks.SIGNAL_FILE
PROGRESS_FILES = ("task_progress.txt", checks.PROGRESS_FILE)
TIMELINE_FILE = "timeline.jsonl"
ARCHIVE_DIR_NAMES = ("hist_tasks", "dead_tasks")
MANUAL_OVERRIDE_FILE = ".manual_override"
JOURNAL_DIR = ".parent"
JOURNAL_FILE = "recovery_journal.jsonl"

STATUS_CONSISTENT = "CONSISTENT"
STATUS_INCONSISTENT = "INCONSISTENT"

CLASS_CONSISTENT = "CONSISTENT"
CLASS_TERMINAL_ARCHIVE_DRIFT = "TERMINAL_ARCHIVE_DRIFT"
CLASS_PROGRESS_SIGNAL_DRIFT = "PROGRESS_SIGNAL_DRIFT"

ACTION_NOOP = "NOOP"
ACTION_ALIGN_TO_ARCHIVE = "ALIGN_TO_ARCHIVE"
ACTION_ALIGN_SIGNAL_TO_PROGRESS = "ALIGN_SIGNAL_TO_PROGRESS"
ACTION_REFUSED_MANUAL_OVERRIDE = "REFUSED_MANUAL_OVERRIDE"

REPORT_STORES = ("archive", "signal", "progress", "timeline")

SOURCE_ARCHIVE = "archive"
SOURCE_SIGNAL = "signal"
SOURCE_TIMELINE = "timeline"
SOURCE_PROGRESS = "progress"
SOURCE_NONE = "none"

SOURCE_BY_ARCHIVE = {
    "hist_tasks": TaskState.COMPLETED,
    "dead_tasks": TaskState.FAILED,
}

#: Declared state vocabulary. An unknown token is absent evidence, never a
#: guess: the checks module's doctrine applied to the state machine.
TOKEN_TO_STATE: dict[str, TaskState] = {
    "done": TaskState.COMPLETED,
    "rejected": TaskState.CANCELLED,
    "error": TaskState.FAILED,
    "running": TaskState.RUNNING,
    "in progress": TaskState.IN_PROGRESS,
    "in_progress": TaskState.IN_PROGRESS,
    "pending": TaskState.PENDING,
}

#: Token written by a repair: the token is the contract, not the original
#: formatting (the pre-image lives in the journal).
TOKEN_BY_STATE: dict[TaskState, str] = {
    TaskState.COMPLETED: "Done",
    TaskState.FAILED: "Error",
    TaskState.CANCELLED: "Rejected",
    TaskState.RUNNING: "Running 1",
    TaskState.IN_PROGRESS: "In Progress",
    TaskState.PENDING: "Pending",
}

TERMINAL_STATES = frozenset({TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED})
ACTIVE_STATES = frozenset({TaskState.PENDING, TaskState.IN_PROGRESS, TaskState.RUNNING})

#: Evidence cap for a single journal ``before`` image.
HEAD_LIMIT = checks.SIGNAL_READ_LIMIT

#: Bounded tail window for the (rotatable, unbounded) progress store. Only the
#: last non-empty line is evidence, so a seek-from-EOF window is enough; the
#: window is larger than ``HEAD_LIMIT`` so a normal line survives whole and a
#: pathological line is truncated instead of the whole file being read.
PROGRESS_TAIL_LIMIT = 65536

#: Bound on retained signal-rejection findings: every element is copied into
#: every report, so the buffer is bounded like the engine's event deque.
FINDINGS_MAXLEN = 64


class UnknownTaskError(KeyError):
    """Raised by the report route for a task id the orchestrator does not track."""


def group_of(state: str | TaskState | None) -> str:
    """``"terminal"``, ``"active"`` or ``"absent"`` for a state value."""

    resolved = _as_state(state)
    if resolved is None:
        return "absent"
    return "terminal" if resolved in TERMINAL_STATES else "active"


def state_for_token(text: str | None) -> TaskState | None:
    """Map a declared token (``"Done"``, ``"running 1"``) onto a ``TaskState``."""

    if not text:
        return None
    stripped = text.strip()
    if not stripped:
        return None
    lowered = stripped.lower()
    if lowered in TOKEN_TO_STATE:
        return TOKEN_TO_STATE[lowered]
    # Declared signals carry the dispatch generation as a suffix (``"In
    # Progress 5"``, ``"Done 5"``), so the longest matching prefix wins: the
    # two-word form first, then the single word.
    words = lowered.split()
    for size in (2, 1):
        if len(words) > size:
            candidate = " ".join(words[:size])
            if candidate in TOKEN_TO_STATE:
                return TOKEN_TO_STATE[candidate]
    return None


def token_for_state(state: str | TaskState | None) -> str | None:
    """Tokens a repair may write for ``state`` (``None`` when not writable)."""

    resolved = _as_state(state)
    if resolved is None:
        return None
    # Only terminal evidence can be the canonical state of a drifted task
    # (``INV-CON-1``: archive > terminal signal > timeline > progress). Writing
    # an active token would forge a generation number the orchestrator never
    # issued, so an active ``state`` is not writable evidence.
    if resolved not in TERMINAL_STATES:
        return None
    return TOKEN_BY_STATE.get(resolved)


def _as_state(value: str | TaskState | None) -> TaskState | None:
    if value is None:
        return None
    if isinstance(value, TaskState):
        return value
    try:
        return TaskState.parse(str(value))
    except ValueError:
        return None


def _first_nonempty_line(text: str) -> str | None:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped:
            return stripped
    return None


def _last_nonempty_line(text: str) -> str | None:
    found: str | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped:
            found = stripped
    return found


def _cap(text: str | None) -> str | None:
    """Bound one evidence string to ``HEAD_LIMIT`` (journal + report cap)."""

    if text is None or len(text) <= HEAD_LIMIT:
        return text
    return text[:HEAD_LIMIT]


def _bounded(value: Any) -> Any:
    """Recursively cap every string in a finding-like structure."""

    if isinstance(value, str):
        return _cap(value)
    if isinstance(value, Mapping):
        return {key: _bounded(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_bounded(item) for item in value]
    return value


def _read_tail(path: Path, limit: int) -> str:
    """Decode the last ``limit`` bytes of ``path`` (bounded read, no whole file).

    The window always ends at EOF, so the *last* line is intact unless that one
    line alone exceeds the window — in which case the truncated tail is capped
    by :func:`_cap` before it is used as evidence.
    """

    with path.open("rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        handle.seek(max(0, size - limit))
        return handle.read(limit).decode("utf-8", errors="replace")


@dataclass(frozen=True)
class StoreReading:
    """One store's view of one task: presence, derived state and raw evidence."""

    present: bool = False
    state: str | None = None
    evidence: str | None = None
    name: str | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"present": self.present, "state": self.state}
        if self.name is not None:
            payload["name"] = self.name
        return payload


@dataclass(frozen=True)
class TaskStoreSnapshot:
    """The four store readings of one task plus the derived canonical state."""

    task: str
    signal: StoreReading
    progress: StoreReading
    timeline: StoreReading
    archive: StoreReading
    manual_override: bool = False
    symlinked: bool = False
    canonical: str | None = None
    canonical_source: str = SOURCE_NONE
    drift_class: str = CLASS_CONSISTENT
    action: str = ACTION_NOOP

    @property
    def status(self) -> str:
        """Drift, not reparability, decides the status (a refusal is drift)."""

        return (
            STATUS_CONSISTENT
            if self.drift_class == CLASS_CONSISTENT
            else STATUS_INCONSISTENT
        )

    @property
    def before_image(self) -> dict[str, Any]:
        """Journal pre-image: what the signal said before any repair."""

        return {
            "signal_head": self.signal.evidence,
            "signal_state": self.signal.state,
            "progress_last": self.progress.evidence,
            "canonical": self.canonical,
            "canonical_source": self.canonical_source,
            "archive": self.archive.name,
        }

    def to_report(self, *, applied: bool = False, reason: str = "check_only") -> dict[str, Any]:
        """Per-task report entry (the route's read-only view by default)."""

        return {
            "task": self.task,
            "status": self.status,
            "class": self.drift_class,
            "canonical": self.canonical,
            "canonical_source": self.canonical_source,
            "stores": {
                "signal": self.signal.to_dict(),
                "progress": self.progress.to_dict(),
                "timeline": self.timeline.to_dict(),
                "archive": self.archive.to_dict(),
            },
            "evidence": {
                "signal_head": self.signal.evidence,
                "progress_last": self.progress.evidence,
            },
            "recovery": {
                "action": self.action,
                "applied": applied,
                "reason": reason,
            },
        }


@dataclass(frozen=True)
class StoreRoots:
    """Resolved store locations; every component is optional (absent evidence)."""

    state_root: Path | None = None
    archive_roots: tuple[tuple[str, Path], ...] = ()
    timeline_path: Path | None = None
    journal_path: Path | None = None

    @classmethod
    def for_state_root(
        cls, state_root: str | Path, *, journal_path: str | Path | None = None
    ) -> "StoreRoots":
        """Derive the sibling stores from an ``active_task`` directory.

        The orchestrator project root is the parent of ``active_task``; that is
        where ``timeline.jsonl``, the archive trees and the journal live.
        """

        root = Path(state_root)
        parent = root.parent
        archives = tuple((name, parent / name) for name in ARCHIVE_DIR_NAMES)
        if journal_path is None:
            journal = parent / JOURNAL_DIR / JOURNAL_FILE
        else:
            journal = Path(journal_path)
        return cls(
            state_root=root,
            archive_roots=archives,
            timeline_path=parent / TIMELINE_FILE,
            journal_path=journal,
        )


# --------------------------------------------------------------------------- #
# Store readers (every one of them is tolerant: a missing file is evidence)
# --------------------------------------------------------------------------- #


def _read_signal(task_dir: Path) -> StoreReading:
    path = task_dir / SIGNAL_FILE
    try:
        if not path.is_file():
            return StoreReading()
        with path.open(encoding="utf-8", errors="replace") as handle:
            head = _first_nonempty_line(handle.read(HEAD_LIMIT))
    except OSError:
        return StoreReading()
    return StoreReading(present=True, state=token_state(head), evidence=head)


def _read_progress(task_dir: Path) -> StoreReading:
    """Read the *tail* of the progress store: bounded, never the whole file.

    Plan §5: "line-limited tail scan for logs". One unauthenticated report can
    visit every task, so an unbounded read here would fan out into a full-file
    read of every progress log on each request.
    """

    present = False
    for name in PROGRESS_FILES:
        path = task_dir / name
        try:
            if not path.is_file():
                continue
            present = True
            last = _last_nonempty_line(_read_tail(path, PROGRESS_TAIL_LIMIT))
        except OSError:
            continue
        if last is not None:
            bounded = _cap(last)
            return StoreReading(
                present=True, state=token_state(bounded), evidence=bounded
            )
    return StoreReading(present=present)


def _read_timeline(path: Path | None, task_id: str) -> StoreReading:
    if path is None:
        return StoreReading()
    try:
        if not path.is_file():
            return StoreReading()
    except OSError:
        return StoreReading()
    state: str | None = None
    evidence: str | None = None
    try:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:  # streamed: the journal is append-only and unbounded
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    record = json.loads(stripped)
                except ValueError:
                    continue
                if not isinstance(record, dict) or record.get("task") != task_id:
                    continue
                resolved = _timeline_state(record)
                if resolved is not None:
                    state, evidence = resolved, _cap(stripped)
    except OSError:
        return StoreReading(present=True)
    return StoreReading(present=True, state=state, evidence=evidence)


def _timeline_state(record: Mapping[str, Any]) -> str | None:
    for key in ("state", "status"):
        raw = record.get(key)
        resolved = _as_state(raw if isinstance(raw, str) else None)
        if resolved is not None:
            return resolved.name
        if isinstance(raw, str):
            mapped = state_for_token(raw)
            if mapped is not None:
                return mapped.name
    return None


def _read_archive(
    roots: tuple[tuple[str, Path], ...], task_id: str
) -> StoreReading:
    for name, root in roots:
        resolved = SOURCE_BY_ARCHIVE.get(name)
        if resolved is None:
            continue
        try:
            archived = (root / task_id).exists()
        except OSError:
            archived = False
        if archived:
            return StoreReading(
                present=True, state=resolved.name, evidence=name, name=name
            )
    return StoreReading()


def token_state(text: str | None) -> str | None:
    """Token -> state *name*, as used by snapshots and the report."""

    resolved = state_for_token(text)
    return None if resolved is None else resolved.name


# --------------------------------------------------------------------------- #
# Classification (``INV-CON-1`` .. ``INV-CON-3``)
# --------------------------------------------------------------------------- #


def _canonical_state(
    signal: StoreReading,
    timeline: StoreReading,
    progress: StoreReading,
    archive: StoreReading,
) -> tuple[str | None, str]:
    """First authority that answers wins (``INV-CON-1``)."""

    if archive.present and archive.state is not None:
        return archive.state, SOURCE_ARCHIVE
    if signal.present and group_of(signal.state) == "terminal":
        return signal.state, SOURCE_SIGNAL  # terminal evidence is sticky
    if timeline.present and timeline.state is not None:
        return timeline.state, SOURCE_TIMELINE
    if progress.present and progress.state is not None:
        return progress.state, SOURCE_PROGRESS
    if signal.present and signal.state is not None:
        return signal.state, SOURCE_SIGNAL
    return None, SOURCE_NONE


def read_snapshot(task_id: str, roots: StoreRoots) -> TaskStoreSnapshot:
    """Read every store of ``task_id`` and classify the combined snapshot."""

    task_dir: Path | None = None
    if roots.state_root is not None:
        task_dir = roots.state_root / task_id

    signal = StoreReading()
    progress = StoreReading()
    manual_override = False
    symlinked = False
    if task_dir is not None:
        try:
            symlinked = task_dir.is_symlink()
            target = task_dir.resolve() if symlinked else task_dir
        except OSError:  # pragma: no cover - defensive
            target = task_dir
        if symlinked:
            # A symlinked slot can point outside the state root; never rewrite
            # through it (the repair target must stay inside our own stores).
            signal = _read_signal(target)
            progress = _read_progress(target)
        else:
            signal = _read_signal(task_dir)
            progress = _read_progress(task_dir)
        try:
            manual_override = (task_dir / MANUAL_OVERRIDE_FILE).exists()
        except OSError:  # pragma: no cover - defensive
            manual_override = False

    timeline = _read_timeline(roots.timeline_path, task_id)
    archive = _read_archive(roots.archive_roots, task_id)
    canonical, source = _canonical_state(signal, timeline, progress, archive)

    drift_class, action = classify(signal, canonical, archive)
    if manual_override and action != ACTION_NOOP:
        action = ACTION_REFUSED_MANUAL_OVERRIDE
    if symlinked and action != ACTION_NOOP:
        action = ACTION_NOOP  # reported through ``recovery.reason`` instead

    return TaskStoreSnapshot(
        task=task_id,
        signal=signal,
        progress=progress,
        timeline=timeline,
        archive=archive,
        manual_override=manual_override,
        symlinked=symlinked,
        canonical=canonical,
        canonical_source=source,
        drift_class=drift_class,
        action=action,
    )


def classify(
    signal: StoreReading, canonical: str | None, archive: StoreReading
) -> tuple[str, str]:
    """``(class, action)`` for one snapshot (``INV-CON-2``)."""

    if not signal.present or signal.state is None or canonical is None:
        return CLASS_CONSISTENT, ACTION_NOOP
    if group_of(signal.state) == group_of(canonical):
        return CLASS_CONSISTENT, ACTION_NOOP
    if archive.present:
        return CLASS_TERMINAL_ARCHIVE_DRIFT, ACTION_ALIGN_TO_ARCHIVE
    return CLASS_PROGRESS_SIGNAL_DRIFT, ACTION_ALIGN_SIGNAL_TO_PROGRESS


# --------------------------------------------------------------------------- #
# Root resolution: no new config knob, reuse the checks layer
# --------------------------------------------------------------------------- #


def resolve_roots(
    *,
    config_path: str | Path | None = None,
    maintainer_root: str | Path | None = None,
    env: Mapping[str, str] | None = None,
) -> StoreRoots:
    """Resolve the stores from the configuration the maintainer already reads."""

    values = checks.resolve_layer_values(
        config_path=config_path, maintainer_root=maintainer_root, env=env
    )
    paths = checks.resolve_path_fields(values)
    state_root = checks.resolve_state_root(paths)
    if state_root is None:
        return StoreRoots()
    return StoreRoots.for_state_root(state_root)


def _known_task_ids(roots: StoreRoots) -> list[str]:
    """Union of the active slots and both archives, sorted for a stable report."""

    ids: set[str] = set()
    if roots.state_root is not None:
        try:
            ids.update(entry.name for entry in roots.state_root.iterdir() if entry.is_dir())
        except OSError:
            pass
    for _, root in roots.archive_roots:
        try:
            ids.update(entry.name for entry in root.iterdir() if entry.is_dir())
        except OSError:
            continue
    return sorted(ids)


def _reason_for(snapshot: TaskStoreSnapshot, default: str) -> str:
    if snapshot.symlinked:
        return "skipped_symlink"
    if snapshot.manual_override and snapshot.action == ACTION_REFUSED_MANUAL_OVERRIDE:
        return "manual_override"
    return default


# --------------------------------------------------------------------------- #
# Service
# --------------------------------------------------------------------------- #


class ConsistencyService:
    """Classify task state across stores and repair it under an audit journal."""

    def __init__(
        self,
        roots: StoreRoots,
        *,
        audit: AuditedOperations | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._roots = roots
        self._clock = clock
        self._audit = audit if audit is not None else AuditedOperations(
            roots.journal_path, clock=clock
        )
        self._lock = threading.RLock()
        self._findings: deque[dict[str, Any]] = deque(maxlen=FINDINGS_MAXLEN)

    @classmethod
    def from_environment(cls) -> "ConsistencyService":
        """Service over the roots the ambient configuration resolves to."""

        return cls(resolve_roots())

    @property
    def roots(self) -> StoreRoots:
        return self._roots

    @property
    def audit(self) -> AuditedOperations:
        return self._audit

    @property
    def findings(self) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._findings)

    def record_finding(self, finding: Mapping[str, Any]) -> dict[str, Any]:
        """Retain one unresolved signal rejection for the report's ``findings[]``."""

        entry = _bounded(dict(finding))
        with self._lock:
            self._findings.append(entry)
        return entry

    def task_ids(self) -> list[str]:
        with self._lock:
            return _known_task_ids(self._roots)

    def snapshot(self, task_id: str) -> TaskStoreSnapshot:
        with self._lock:
            return read_snapshot(task_id, self._roots)

    # ---------------------------------------------------------------- reporting

    def report(self, task: str | None = None) -> dict[str, Any]:
        """Read-only consistency report; ``task`` narrows it to one task id."""

        with self._lock:
            if task is None:
                snapshots = [read_snapshot(tid, self._roots) for tid in _known_task_ids(self._roots)]
            else:
                known = _known_task_ids(self._roots)
                if task not in known:
                    raise UnknownTaskError(task)
                snapshots = [read_snapshot(task, self._roots)]

            entries: list[dict[str, Any]] = []
            for snapshot in snapshots:
                entry = snapshot.to_report(
                    applied=False, reason=_reason_for(snapshot, "check_only")
                )
                entries.append(entry)

            inconsistent = [e for e in entries if e["status"] == STATUS_INCONSISTENT]
            return {
                "status": STATUS_INCONSISTENT if inconsistent else STATUS_CONSISTENT,
                "now": self._clock(),
                "task_count": len(entries),
                "inconsistent_count": len(inconsistent),
                "stores": list(REPORT_STORES),
                "tasks": entries,
                "findings": list(self._findings),
            }

    # ---------------------------------------------------------------- recovery

    def recover_all(self) -> dict[str, Any]:
        """Repair every inconsistent task; return the startup-job summary."""

        summary: dict[str, Any] = {
            "scanned": 0,
            "inconsistent": 0,
            "recovered": 0,
            "refused": 0,
            "failed": 0,
            "journal": None
            if self._roots.journal_path is None
            else str(self._roots.journal_path),
            "tasks": [],
        }
        with self._lock:
            for task_id in _known_task_ids(self._roots):
                summary["scanned"] += 1
                try:
                    result = self._recover_one(task_id)
                except Exception as error:  # pragma: no cover - defensive
                    summary["failed"] += 1
                    summary["tasks"].append(
                        {
                            "task": task_id,
                            "action": "ERROR",
                            "applied": False,
                            "reason": type(error).__name__,
                        }
                    )
                    continue
                if result["status"] == STATUS_INCONSISTENT:
                    summary["inconsistent"] += 1
                if result["recovery"]["applied"]:
                    summary["recovered"] += 1
                elif result["recovery"]["action"] == ACTION_REFUSED_MANUAL_OVERRIDE:
                    summary["refused"] += 1
                elif result["recovery"]["reason"] == "write_failed":
                    summary["failed"] += 1
                summary["tasks"].append({"task": task_id, **result["recovery"]})
        return summary

    def _recover_one(self, task_id: str) -> dict[str, Any]:
        snapshot = read_snapshot(task_id, self._roots)
        action = snapshot.action

        if action == ACTION_NOOP:
            reason = _reason_for(snapshot, "consistent")
            return snapshot.to_report(applied=False, reason=reason)

        if action == ACTION_REFUSED_MANUAL_OVERRIDE:
            entry = self._audit.new_entry(
                OperationType.CONSISTENCY_RECOVERY,
                task=task_id,
                store=SIGNAL_FILE,
                action=ACTION_REFUSED_MANUAL_OVERRIDE,
                before=snapshot.before_image,
            )
            # A refusal changes nothing, so it is journalled as a single
            # terminal line: there is no write for a second line to confirm.
            self._audit.record(replace(entry, phase=PHASE_REFUSED))
            return snapshot.to_report(applied=False, reason="manual_override")

        token = token_for_state(snapshot.canonical)
        if token is None:  # pragma: no cover - defensive
            return snapshot.to_report(applied=False, reason="no_writable_token")

        before = snapshot.before_image
        entry = self._audit.new_entry(
            OperationType.CONSISTENCY_RECOVERY,
            task=task_id,
            store=SIGNAL_FILE,
            action=action,
            before=before,
        )
        self._audit.record(entry)  # INV-AUDIT-1: journal before the first byte
        after = {
            "signal_head": token,
            "signal_state": snapshot.canonical,
            "canonical": snapshot.canonical,
            "canonical_source": snapshot.canonical_source,
        }
        try:
            self._write_signal(task_id, token)
        except OSError as error:
            self._audit.complete(
                entry, PHASE_FAILED, {**after, "error": type(error).__name__}
            )
            return snapshot.to_report(applied=False, reason="write_failed")
        self._audit.complete(entry, PHASE_SUCCEEDED, after)
        return snapshot.to_report(applied=True, reason="recovered")

    def _write_signal(self, task_id: str, token: str) -> None:
        """Replace ``_signal.txt`` atomically (write tmp, then ``os.replace``).

        The classification already skipped a symlinked slot, but classify and
        write are two separate syscalls: the slot is therefore re-checked
        immediately before the temp file is created *and* again immediately
        before the replace, so a swap that happens in between fails the repair
        (``FAILED`` journal line, stores untouched) instead of writing outside
        the state root. The temp file is opened ``O_NOFOLLOW`` so the write
        itself cannot follow a planted link.
        """

        state_root = self._roots.state_root
        if state_root is None:  # pragma: no cover - defensive
            raise OSError("no state root")
        task_dir = state_root / task_id
        _guard_writable_slot(task_dir, state_root)
        target = task_dir / SIGNAL_FILE
        tmp = task_dir / (SIGNAL_FILE + ".tmp")
        flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(tmp, flags, 0o644)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(token + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        try:
            _guard_writable_slot(task_dir, state_root)  # narrows the swap window
            os.replace(tmp, target)
        except OSError:
            try:
                tmp.unlink()
            except OSError:  # pragma: no cover - defensive
                pass
            raise


def _guard_writable_slot(task_dir: Path, state_root: Path) -> None:
    """Refuse to write through a slot that is a symlink or escapes the root."""

    if task_dir.is_symlink():
        raise OSError("symlinked task slot")
    resolved_root = state_root.resolve()
    resolved = task_dir.resolve()
    if resolved != resolved_root and resolved_root not in resolved.parents:
        raise OSError("task slot escapes the state root")


# --------------------------------------------------------------------------- #
# Startup job (REQ-02)
# --------------------------------------------------------------------------- #


def run_startup_recovery(
    roots: StoreRoots | None = None,
    *,
    audit: AuditedOperations | None = None,
    clock: Callable[[], float] = time.time,
) -> dict[str, Any]:
    """Framework-startup entry point: scan every known task and repair it.

    Called once from :func:`ai_maintainer.api.serve` without arguments. Returns
    ``{"scanned", "inconsistent", "recovered", "refused", "failed", "journal",
    "tasks"}``; the job never raises — an unreadable store is skipped and a
    failed write leaves a ``FAILED`` journal line plus the untouched store.
    """

    service = ConsistencyService(
        roots if roots is not None else resolve_roots(), audit=audit, clock=clock
    )
    return service.recover_all()
