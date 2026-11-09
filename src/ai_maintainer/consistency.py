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
from collections.abc import Collection, Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable, Mapping

from ai_maintainer import checks
from ai_maintainer.audit import (
    PHASE_FAILED,
    PHASE_PENDING,
    PHASE_REFUSED,
    PHASE_SUCCEEDED,
    AuditedOperations,
    OperationType,
    entries_by_id,
    read_journal,
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
    # Maintainer-memory record validators (UTILTOOLS-72 REQ-01).
    "ERROR_CODES",
    "validate_bug_fix_integrity",
    "validate_learning_dedup",
    "validate_memory_document",
    "validate_memory_records",
    "validate_run_integrity",
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

#: Journal reconciliation (REQ-01..REQ-04): the ``after{}`` key that marks a
#: terminal line as produced by the reconciliation pass rather than by a live
#: repair, plus its outcome vocabulary.
JOURNAL_RECONCILIATION = "journal_reconciliation"
RECONCILIATION_WRITE_CONFIRMED = "WRITE_CONFIRMED"
RECONCILIATION_WRITE_NOT_APPLIED = "WRITE_NOT_APPLIED"
RECONCILIATION_REFUSED = "REFUSED"

#: Bound on the per-entry detail list in the reconciliation counters; counters
#: themselves stay exact, so a journal with thousands of orphans cannot bloat
#: the startup summary.
RECONCILIATION_ENTRIES_LIMIT = 50

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
    return _read_signal_checked(task_dir)[0]


def _read_signal_checked(task_dir: Path) -> tuple[StoreReading, bool]:
    """``(reading, unreadable)`` for one slot's signal store.

    Same evidence as :func:`_read_signal`, but distinguishes *absent* from
    *unreadable*: a ``_signal.txt`` that exists yet cannot be opened (or is a
    symlink that would lead outside the slot) is reported as ``unreadable`` so
    a caller that must not guess -- the journal reconciliation pass -- can
    refuse instead of asserting the file is gone.
    """

    path = task_dir / SIGNAL_FILE
    try:
        if path.is_symlink():
            return StoreReading(), True
        if not path.is_file():
            return StoreReading(), False
        with path.open(encoding="utf-8", errors="replace") as handle:
            head = _first_nonempty_line(handle.read(HEAD_LIMIT))
    except OSError:
        return StoreReading(), True
    return StoreReading(present=True, state=token_state(head), evidence=head), False


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
# Journal reconciliation (REQ-01 .. REQ-04)
# --------------------------------------------------------------------------- #


def _reconcile_target_task(line: Mapping[str, Any], roots: StoreRoots) -> str | None:
    """``task`` of a journal line, but only as one safe component (REQ-02).

    A journal line is untrusted input (another process, a restored backup or a
    hand-edited file can append anything), so the field is only accepted when
    it is a non-empty string without separators, ``.``/``..`` or ``NUL`` *and*
    the resolved slot still lives inside ``state_root``. Anything else returns
    ``None`` and can at worst produce a ``REFUSED`` line — never a read outside
    the state root, and never a store write (this pass writes only the journal).
    """

    task = line.get("task")
    if not isinstance(task, str) or not task:
        return None
    if "\x00" in task or task in (".", ".."):
        return None
    if "/" in task or "\\" in task:
        return None
    for separator in (os.sep, os.altsep):
        if separator and separator in task:
            return None
    state_root = roots.state_root
    if state_root is None:
        return None
    task_dir = state_root / task
    try:
        if task_dir.is_symlink():
            return None
        resolved_root = state_root.resolve()
        resolved = task_dir.resolve()
    except OSError:
        return None
    if resolved != resolved_root and resolved_root not in resolved.parents:
        return None
    return task


def _live_signal(roots: StoreRoots, task_id: str) -> tuple[bool, str | None, bool]:
    """``(present, head, flagged)`` of the live ``_signal.txt`` for a journal line.

    Read-only: :func:`_read_signal_checked` opens the file for reading and never
    writes, and a missing file is absent evidence (``present is False``,
    ``flagged is False``). A slot that turned into a symlink or escaped the root
    after :func:`_reconcile_target_task` vetted it -- or a signal store that
    exists but cannot be read -- is reported as ``flagged``: the pass never
    obtained evidence, so plan §4a/§4b row 6 requires ``REFUSED``
    (``reason="unreadable_store"``) rather than a positive
    ``WRITE_NOT_APPLIED`` claim.
    """

    state_root = roots.state_root
    if state_root is None:  # pragma: no cover - defensive
        return False, None, True
    task_dir = state_root / task_id
    try:
        if task_dir.is_symlink():
            return False, None, True
        resolved_root = state_root.resolve()
        resolved = task_dir.resolve()
    except OSError:
        return False, None, True
    if resolved != resolved_root and resolved_root not in resolved.parents:
        return False, None, True
    reading, unreadable = _read_signal_checked(task_dir)
    return reading.present, reading.evidence, unreadable


def _string_field(source: Mapping[str, Any] | None, key: str) -> str | None:
    """One capped string field of a journal pre-image (non-strings are dropped)."""

    if source is None:
        return None
    value = source.get(key)
    return _cap(value) if isinstance(value, str) else None


def _note(
    counters: dict[str, Any],
    entry_id: str,
    outcome: str,
    error: str | None = None,
) -> None:
    """Append one bounded per-entry detail line to the counters."""

    entries = counters["entries"]
    if len(entries) >= RECONCILIATION_ENTRIES_LIMIT:
        counters["entries_truncated"] = True
        return
    entries.append({"entry_id": entry_id, "outcome": outcome, "error": error})


def _resolve_orphan(
    roots: StoreRoots, line: Mapping[str, Any]
) -> tuple[str, dict[str, Any]]:
    """Decide the missing terminal phase of one orphan ``PENDING`` line.

    Pure: classifies against the PENDING pre-image and the *current* snapshot
    only. The ordered checks are the plan's §4b table — the expected token
    first, then the pre-image, then absence, then any third value. Row order
    matters: a pre-image that already equals the expected token is
    ``WRITE_CONFIRMED``, never ``WRITE_NOT_APPLIED``.
    """

    before = line.get("before")
    before = before if isinstance(before, Mapping) else None
    canonical = before.get("canonical") if before is not None else None
    expected = token_for_state(canonical if isinstance(canonical, str) else None)
    op = line.get("op")
    op = op.value if isinstance(op, OperationType) else op
    pre_head = _string_field(before, "signal_head")
    # Plan §4b: every field copied into ``after`` is bounded by ``_cap``.
    capped_canonical = _cap(canonical if isinstance(canonical, str) else None)
    base = {
        JOURNAL_RECONCILIATION: RECONCILIATION_REFUSED,
        "signal_head": pre_head,
        "expected_token": expected,
        "canonical": capped_canonical,
    }

    if op != OperationType.CONSISTENCY_RECOVERY.value:
        return PHASE_REFUSED, {**base, "reason": "unsupported_op"}
    if before is None or not isinstance(canonical, str):
        return PHASE_REFUSED, {**base, "reason": "malformed_before"}
    if expected is None:
        return PHASE_REFUSED, {**base, "reason": "no_writable_token"}

    task = _reconcile_target_task(line, roots)
    if task is None:
        return PHASE_REFUSED, {**base, "reason": "invalid_task"}
    present, head, flagged = _live_signal(roots, task)
    if flagged:
        # No evidence was obtained, so neither confirm nor deny the write.
        return PHASE_REFUSED, {**base, "reason": "unreadable_store"}

    canonical_source = _string_field(before, "canonical_source")
    if head == expected:
        return PHASE_SUCCEEDED, {
            JOURNAL_RECONCILIATION: RECONCILIATION_WRITE_CONFIRMED,
            "signal_head": head,
            "expected_token": expected,
            "canonical": capped_canonical,
            "canonical_source": canonical_source,
        }
    not_applied = {
        JOURNAL_RECONCILIATION: RECONCILIATION_WRITE_NOT_APPLIED,
        "signal_head": head,
        "signal_present": present,
        "expected_token": expected,
        "canonical": capped_canonical,
        "canonical_source": canonical_source,
    }
    if present and head == pre_head:
        return PHASE_FAILED, not_applied
    if not present:
        return PHASE_FAILED, {**not_applied, "reason": "signal_absent"}
    return PHASE_REFUSED, {**base, "signal_head": head, "reason": "head_diverged"}


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

    def reconcile_journal(self) -> dict[str, Any]:
        """Terminalise every orphan ``PENDING`` group in ``recovery_journal.jsonl``.

        A crash between a repair's ``PENDING`` line and its terminal line leaves
        the journal claiming an operation is in flight forever. This pass reads
        the journal under the service lock, resolves each orphan against the
        *live* signal head through its pre-image (REQ-02) and appends the missing
        terminal line with :meth:`AuditedOperations.append_terminal`.

        Append-only and read-only otherwise (INV-JR-2): no store, archive, or
        timeline byte is written, and no ``PENDING`` line is ever appended
        (INV-JR-3). Terminal groups are skipped, so a second call reports
        ``scanned_pending == reconciled == 0`` and appends nothing (INV-JR-4);
        a journal path of ``None`` is a no-op. One failing entry is counted in
        ``failed`` and never aborts the pass (INV-JR-5).

        Returns ``{"journal", "scanned_pending", "confirmed", "not_applied",
        "refused", "failed", "reconciled", "entries", "entries_truncated"}``.
        The lock is in-process only: cross-process atomicity is not claimed, and
        a duplicate terminal line from a racing process is harmless because the
        last phase of the group is then terminal and no store write follows.
        """

        with self._lock:
            journal_path = self._roots.journal_path
            counters: dict[str, Any] = {
                "journal": None if journal_path is None else str(journal_path),
                "scanned_pending": 0,
                "confirmed": 0,
                "not_applied": 0,
                "refused": 0,
                "failed": 0,
                "reconciled": 0,
                "entries": [],
                "entries_truncated": False,
            }
            if journal_path is None:
                return counters
            for entry_id, group in entries_by_id(read_journal(journal_path)).items():
                last = group[-1]
                if last.get("phase") != PHASE_PENDING:
                    continue
                counters["scanned_pending"] += 1
                try:
                    phase, after = _resolve_orphan(self._roots, last)
                    self._audit.append_terminal(last, phase, after)
                except OSError as error:
                    counters["failed"] += 1
                    _note(counters, entry_id, "journal_write_failed", type(error).__name__)
                    continue
                except Exception as error:  # pragma: no cover - defensive
                    counters["failed"] += 1
                    _note(counters, entry_id, "reconcile_error", type(error).__name__)
                    continue
                counters[
                    {
                        PHASE_SUCCEEDED: "confirmed",
                        PHASE_FAILED: "not_applied",
                        PHASE_REFUSED: "refused",
                    }[phase]
                ] += 1
                _note(counters, entry_id, after[JOURNAL_RECONCILIATION], None)
            counters["reconciled"] = (
                counters["confirmed"] + counters["not_applied"] + counters["refused"]
            )
            return counters

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
            "journal_reconciliation": None,
            "tasks": [],
        }
        with self._lock:
            # REQ-03: reconcile torn journal pairs strictly *before* the scan, so
            # the PENDING lines this run writes are never mistaken for orphans.
            try:
                summary["journal_reconciliation"] = self.reconcile_journal()
            except Exception as error:  # pragma: no cover - defensive
                summary["journal_reconciliation"] = {
                    "journal": summary["journal"],
                    "scanned_pending": 0,
                    "confirmed": 0,
                    "not_applied": 0,
                    "refused": 0,
                    "failed": 1,
                    "reconciled": 0,
                    "entries": [
                        {"outcome": "reconcile_error", "error": type(error).__name__}
                    ],
                    "entries_truncated": False,
                }
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
    "journal_reconciliation", "tasks"}``; the job never raises — an unreadable
    store is skipped, a failed write leaves a ``FAILED`` journal line plus the
    untouched store, and ``journal_reconciliation`` carries the torn-pair
    counters (``scanned_pending``, ``confirmed``, ``not_applied``, ``refused``,
    ``failed``) of the pass that ran before the scan.
    """

    service = ConsistencyService(
        roots if roots is not None else resolve_roots(), audit=audit, clock=clock
    )
    return service.recover_all()


# --------------------------------------------------------------------------- #
# Maintainer-memory record validators (UTILTOOLS-72 REQ-01)
# Additive half: the service above repairs the task *signal* store; these read
# the memory document, report what is wrong and repair nothing. Each returns
# ``list[str]`` of ``CODE: detail`` lines from :data:`ERROR_CODES`.
# --------------------------------------------------------------------------- #

ERROR_CODES = (
    "MEMORY_CORRUPT",
    "RUN_DUPLICATE_RUN_ID",
    "RUN_MISSING_FIELD",
    "BUG_FIX_DUPLICATE_FIX_ID",
    "BUG_FIX_ORPHAN_TASK",
    "LEARNING_DUPLICATE",
    "LEARNING_MISSING_FIELD",
)


def _records_in(value: Any) -> list[Any] | None:
    """``value`` as a list of records, or ``None`` when it holds none (no raise)."""

    if isinstance(value, (str, bytes, bytearray, Mapping)):
        return None
    return list(value) if isinstance(value, Sequence) else None


def _duplicate_ids(records: list[Any], field: str, code: str, label: str) -> list[str]:
    errors: list[str] = []
    seen: dict[str, int] = {}
    for index, record in enumerate(records):
        if not isinstance(record, Mapping):
            continue
        value = record.get(field)
        if not isinstance(value, str) or not value.strip():
            continue
        key = value.strip()
        if key in seen:
            errors.append(
                f"{code}: {label}[{index}].{field} {key!r} repeats {label}[{seen[key]}]"
            )
        else:
            seen[key] = index
    return errors


def validate_run_integrity(runs: Sequence[Any]) -> list[str]:
    """Validate ``runs[]``: repeated ``run_id``, then missing run fields."""

    records = _records_in(runs)
    if records is None:
        return []

    errors = _duplicate_ids(records, "run_id", "RUN_DUPLICATE_RUN_ID", "runs")
    for index, run in enumerate(records):
        if not isinstance(run, Mapping):
            error = f"runs[{index}] is not a mapping ({type(run).__name__})"
            errors.append(f"RUN_MISSING_FIELD: {error}")
            continue
        for field_name in ("run_id", "task_id", "state"):
            value = run.get(field_name)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"RUN_MISSING_FIELD: runs[{index}].{field_name} is missing")
    return errors


def validate_bug_fix_integrity(
    bug_fixes: Sequence[Any], *, known_task_ids: Collection[str] | None = None
) -> list[str]:
    """Validate ``bug_fixes[]``: repeated ``fix_id``, then orphaned ``task_id``.

    ``known_task_ids=None`` skips the orphan check.
    """

    records = _records_in(bug_fixes)
    if records is None:
        return []

    errors = _duplicate_ids(records, "fix_id", "BUG_FIX_DUPLICATE_FIX_ID", "bug_fixes")
    if known_task_ids is None:
        return errors

    known = {str(task_id).strip() for task_id in known_task_ids}
    for index, fix in enumerate(records):
        if not isinstance(fix, Mapping):
            continue
        task_id = fix.get("task_id")
        if not isinstance(task_id, str) or not task_id.strip():
            errors.append(f"BUG_FIX_ORPHAN_TASK: bug_fixes[{index}] has no task_id")
        elif task_id.strip() not in known:
            error = f"bug_fixes[{index}].task_id {task_id.strip()!r} is not a known task"
            errors.append(f"BUG_FIX_ORPHAN_TASK: {error}")
    return errors


def validate_learning_dedup(learnings: Sequence[Any]) -> list[str]:
    """Validate ``learnings[]``: duplicate normalised ``text``/id, missing text."""

    records = _records_in(learnings)
    if records is None:
        return []

    errors: list[str] = []
    seen_text: dict[str, int] = {}
    seen_id: dict[str, int] = {}
    for index, learning in enumerate(records):
        if not isinstance(learning, Mapping):
            error = f"learnings[{index}] is not a mapping ({type(learning).__name__})"
            errors.append(f"LEARNING_MISSING_FIELD: {error}")
            continue
        text = learning.get("text")
        text_key = " ".join(text.split()).casefold() if isinstance(text, str) else ""
        learning_id = learning.get("learning_id")
        id_key = learning_id.strip() if isinstance(learning_id, str) else ""

        duplicate = False
        if text_key:
            if text_key in seen_text:
                first = seen_text[text_key]
                errors.append(
                    f"LEARNING_DUPLICATE: learnings[{index}].text repeats learnings[{first}]"
                )
                duplicate = True
            else:
                seen_text[text_key] = index
        if id_key and id_key in seen_id and not duplicate:
            errors.append(
                f"LEARNING_DUPLICATE: learnings[{index}].learning_id {id_key!r} "
                f"repeats learnings[{seen_id[id_key]}]"
            )
        elif id_key and id_key not in seen_id:
            seen_id[id_key] = index
        if not text_key:
            errors.append(f"LEARNING_MISSING_FIELD: learnings[{index}].text is missing")

    return errors


def validate_memory_document(document: Any) -> list[str]:
    """Validate the document envelope: root mapping, ``version 1``, list fields."""

    if not isinstance(document, Mapping):
        error = f"document root must be a mapping ({type(document).__name__})"
        return [f"MEMORY_CORRUPT: {error}"]

    errors: list[str] = []
    version = document.get("version")
    if isinstance(version, bool) or not isinstance(version, int) or version != 1:
        errors.append(f"MEMORY_CORRUPT: unsupported document version {version!r}")
    for name in ("runs", "bug_fixes", "learnings"):
        value = document.get(name)
        if not isinstance(value, list):
            errors.append(f"MEMORY_CORRUPT: {name} must be a list ({type(value).__name__})")
    return errors


def validate_memory_records(
    records: Mapping[str, Any], *, known_task_ids: Collection[str] | None = None
) -> list[str]:
    """Run every check over ``records`` in error-code order; envelope first.

    Shared by ``MemoryStore._save()`` and ``MaintainerEngine._finish_cycle()``;
    a corrupt envelope short-circuits the record checks.
    """

    envelope = validate_memory_document(records)
    if envelope:
        return envelope
    return validate_run_integrity(records["runs"]) + validate_bug_fix_integrity(
        records["bug_fixes"], known_task_ids=known_task_ids
    ) + validate_learning_dedup(records["learnings"])
