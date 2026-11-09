"""Maintainer memory store: one JSON document plus the checks guarding it.

The maintainer accumulates three kinds of record while it works a task queue -
the ``runs`` of the cycle engine, the ``bug_fixes`` it applied and the
``learnings`` it derived. This module keeps all three in a single JSON document
(``<state_root>/maintainer_memory.json``) so a cycle can load it, append to it
and hand it back atomically, and it runs the :mod:`ai_maintainer.consistency`
validators before every write (UTILTOOLS-72 REQ-02).

Design constraints, mirroring the rest of the package:

* the path is injected - this module never guesses the state root and never
  creates the parent directory;
* a write is atomic (``<name>.tmp`` sibling + ``os.replace``) and refuses to
  follow a symlinked document;
* a bad document degrades to an empty store carrying a ``MEMORY_CORRUPT``
  issue; a bad write is logged and swallowed - memory is not a precondition for
  a cycle;
* an export is a copy (sorted on the copy, never in the live store) written
  through :func:`atomic_write_text`, and an import merges into the existing
  document without ever persisting a record the validators reject
  (UTILTOOLS-79 REQ-01/REQ-02).
* nothing here mutates the monitored task or the task signal store.
"""

from __future__ import annotations

import copy
import json
import logging
import os
import stat
import threading
import time
from pathlib import Path
from typing import Any, Callable, Collection, Mapping, Sequence

from ai_maintainer.consistency import (
    validate_bug_fix_integrity,
    validate_learning_dedup,
    validate_memory_document,
    validate_memory_records,
    validate_run_integrity,
)

__all__ = [
    "MEMORY_DOC_NAME",
    "MEMORY_DOC_VERSION",
    "MemoryStore",
    "atomic_write_text",
]

MEMORY_DOC_NAME = "maintainer_memory.json"
MEMORY_DOC_VERSION = 1
MEMORY_READ_LIMIT = 65536
LOG = logging.getLogger(__name__)

_COLLECTIONS = ("runs", "bug_fixes", "learnings")

#: Fields an import uses to recognise a record it has already seen. The first
#: field of each tuple is the brief key of UTILTOOLS-79 REQ-04; the remaining
#: ones are the validators' identity keys, which must also be unique or the
#: next ``_read`` would blank the store.
_DEDUPE_FIELDS: dict[str, tuple[tuple[str, str], ...]] = {
    "runs": (("task_id", "text"), ("run_id", "text")),
    "bug_fixes": (("git_commit", "text"), ("fix_id", "text")),
    "learnings": (("at", "timestamp"), ("learning_id", "text"), ("text", "text")),
}

#: The brief keys alone decide "duplicate"; the rest decide "would break".
_BRIEF_FIELDS: dict[str, tuple[str, ...]] = {
    "runs": ("task_id",),
    "bug_fixes": ("git_commit",),
    "learnings": ("at",),
}

#: REQ-03 required fields per collection; ``runs`` is ordering-only.
_VALIDATE_REQUIRED: dict[str, tuple[str, ...]] = {
    "bug_fixes": ("task_id", "git_commit"),
    "learnings": ("text",),
}

#: REQ-03 error code per collection for :data:`_VALIDATE_REQUIRED`.
_VALIDATE_REQUIRED_CODES: dict[str, str] = {
    "bug_fixes": "BUG_FIX_MISSING_FIELD",
    "learnings": "LEARNING_MISSING_FIELD",
}

#: REQ-03 ``component``; this repo labels a fix with ``summary`` (ASM-01).
_BUG_FIX_LABEL_FIELDS = ("component", "summary")

#: REQ-04 duplicate error code per collection (its brief key is above).
_DUPLICATE_CODES: dict[str, str] = {
    "runs": "RUN_DUPLICATE_TASK_ID",
    "bug_fixes": "BUG_FIX_DUPLICATE_GIT_COMMIT",
    "learnings": "LEARNING_DUPLICATE",
}


def _corrupt_label(issues: Collection[str]) -> str:
    """``MEMORY_CORRUPT: ...`` for the first issue, prefixing it when needed."""

    first = issues[0] if issues else f"{MEMORY_DOC_NAME} is unreadable"
    if first.startswith("MEMORY_CORRUPT"):
        return first
    return f"MEMORY_CORRUPT: {first}"


def _timestamp_order(item: tuple[int, Any]) -> tuple[int, float, int]:
    """Sort key for a ``(index, record)`` pair: ascending ``at``, newest last.

    A record whose ``at`` is missing or not a real number sorts before every
    numeric one; the index keeps the sort stable so the live order is never
    rewritten by a tie.
    """

    index, record = item
    value = record.get("at") if isinstance(record, Mapping) else None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return (0, 0.0, index)
    return (1, float(value), index)


def _dedupe_key(kind: str, value: Any) -> str | None:
    """Normalise one dedupe field; ``None`` when the record does not carry it."""

    if kind == "timestamp":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return None
        return repr(float(value))
    if not isinstance(value, str):
        return None
    if kind == "lesson_text":
        # Same normalisation as ``consistency.validate_learning_dedup``.
        key = " ".join(value.split()).casefold()
    else:
        key = value.strip()
    return key or None


def _dedupe_keys(name: str, record: Mapping[str, Any]) -> set[tuple[str, str]]:
    """Every dedupe key a record carries, as ``(field, normalised value)``."""

    keys: set[tuple[str, str]] = set()
    for field_name, kind in _DEDUPE_FIELDS.get(name, ()):
        key = _dedupe_key(kind, record.get(field_name))
        if key is not None:
            keys.add((field_name, key))
    return keys


def _brief_keys(name: str, record: Mapping[str, Any]) -> set[tuple[str, str]]:
    """The subset of :func:`_dedupe_keys` made of the REQ-04 brief keys."""

    brief = set(_BRIEF_FIELDS.get(name, ()))
    return {key for key in _dedupe_keys(name, record) if key[0] in brief}


def _record_label(name: str, record: Mapping[str, Any]) -> str:
    """One ``field=value`` label for a skip message."""

    for field_name, _kind in _DEDUPE_FIELDS.get(name, ()):
        value = record.get(field_name)
        if value is not None:
            return f"{field_name}={value!r}"
    return "no key field"


def _non_blank(value: Any) -> bool:
    """``True`` when ``value`` is a string with non-whitespace content."""

    return isinstance(value, str) and bool(value.strip())


def _validate_runs(records: Sequence[Any]) -> list[str]:
    """REQ-03 ordering check for ``runs`` (oldest first), plus ``at`` presence."""

    errors: list[str] = []
    previous: tuple[int, tuple[int, float, int]] | None = None
    for index, record in enumerate(records):
        if not isinstance(record, Mapping):
            errors.append(
                f"RUN_MISSING_FIELD: runs[{index}] is not a mapping "
                f"({type(record).__name__})"
            )
            continue
        order = _timestamp_order((index, record))
        if order[0] == 0:
            errors.append(f"MEMORY_ORDER: runs[{index}].at is missing")
            continue
        if previous is not None and order < previous[1]:
            errors.append(
                f"MEMORY_ORDER: runs[{index}].at {order[1]!r} sorts before "
                f"runs[{previous[0]}].at {previous[1][1]!r}"
            )
        previous = (index, order)
    return errors


def _validate_required(name: str, records: Sequence[Any], code: str) -> list[str]:
    """REQ-03 ``code``: every required field of every record must be non-blank."""

    errors: list[str] = []
    for index, record in enumerate(records):
        if not isinstance(record, Mapping):
            errors.append(
                f"{code}: {name}[{index}] is not a mapping "
                f"({type(record).__name__})"
            )
            continue
        for field_name in _VALIDATE_REQUIRED[name]:
            if _non_blank(record.get(field_name)):
                continue
            errors.append(
                f"{code}: {name}[{index}].{field_name} is missing"
            )
        if name == "bug_fixes" and not any(
            _non_blank(record.get(field_name))
            for field_name in _BUG_FIX_LABEL_FIELDS
        ):
            errors.append(
                f"{code}: {name}[{index}].component is missing"
            )
    return errors


def _validate_duplicates(name: str, records: Sequence[Any]) -> list[str]:
    """REQ-04 ``_DUPLICATE_CODES[name]``: repeated key values, naming both indices."""

    code = _DUPLICATE_CODES[name]
    brief = set(_BRIEF_FIELDS.get(name, ()))
    fields = [
        (field_name, kind)
        for field_name, kind in _DEDUPE_FIELDS.get(name, ())
        if field_name in brief
    ]
    errors: list[str] = []
    seen: dict[tuple[str, str], int] = {}
    for index, record in enumerate(records):
        if not isinstance(record, Mapping):
            continue
        for field_name, kind in fields:
            key = _dedupe_key(kind, record.get(field_name))
            if key is None:
                continue
            pair = (field_name, key)
            first = seen.get(pair)
            if first is not None:
                errors.append(
                    f"{code}: {name}[{index}].{field_name} {key!r} repeats "
                    f"{name}[{first}]"
                )
                continue
            seen[pair] = index
    return errors


def _validate_limits(
    name: str, records: Sequence[Any], limit: int | None
) -> list[str]:
    """MEMORY_OVER_LIMIT: ``limit`` (when given) is a count of records."""

    if limit is None or limit < 0 or len(records) <= limit:
        return []
    return [f"MEMORY_OVER_LIMIT: {name} has {len(records)} records, limit is {limit}"]


def _collection_errors(store: "MemoryStore", name: str) -> list[str]:
    """Run the collection's own validator over the store's current records."""

    with store._write_lock:
        records = list(store._records[name])
    if name == "runs":
        return validate_run_integrity(records)
    if name == "bug_fixes":
        return validate_bug_fix_integrity(records)
    return validate_learning_dedup(records)


def _read_import_source(
    source: Mapping[str, Any] | str | Path,
) -> tuple[dict[str, Any] | None, list[str]]:
    """Load the incoming snapshot; a failure is a string, never an exception."""

    if isinstance(source, Mapping):
        document: Any = dict(source)
    else:
        try:
            source_path = Path(source)
        except TypeError:
            return None, ["MEMORY_IMPORT_FAILED: source is not a mapping or a path"]
        try:
            info = os.lstat(source_path)
        except OSError:
            return None, ["MEMORY_IMPORT_FAILED: not a regular file"]
        if not stat.S_ISREG(info.st_mode):
            return None, ["MEMORY_IMPORT_FAILED: not a regular file"]
        if info.st_size > MEMORY_READ_LIMIT:
            return None, [
                f"MEMORY_IMPORT_FAILED: larger than {MEMORY_READ_LIMIT} bytes"
            ]
        try:
            with open(source_path, encoding="utf-8", errors="replace") as handle:
                raw = handle.read(MEMORY_READ_LIMIT)
        except OSError:
            return None, ["MEMORY_IMPORT_FAILED: not a regular file"]
        try:
            document = json.loads(raw)
        except (ValueError, RecursionError):
            return None, ["MEMORY_IMPORT_FAILED: not valid JSON"]
    envelope = validate_memory_document(document)
    if envelope:
        return None, [
            "MEMORY_IMPORT_FAILED: unsupported document version "
            f"({envelope[0]})"
        ]
    return copy.deepcopy(document), []


def atomic_write_text(path: str | Path, text: str) -> None:
    """Replace ``path`` atomically (``.tmp`` sibling + fsync + ``os.replace``).

    The same sequence :meth:`MemoryStore._write` uses, without the JSON step:
    the target is never opened directly, a symlinked target is refused, the
    temporary sibling is opened ``O_NOFOLLOW`` so a planted link cannot redirect
    the write, and the temporary file is removed when anything fails. The parent
    directory is never created - a missing parent surfaces as
    ``FileNotFoundError`` to the caller.
    """

    target = Path(path)
    tmp = target.with_name(target.name + ".tmp")
    if target.is_symlink():
        raise OSError(f"refusing to write through a symlink: {target}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
    handle_fd = os.open(tmp, flags, 0o644)
    try:
        with os.fdopen(handle_fd, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        if target.is_symlink():  # narrow the swap window
            raise OSError(f"refusing to write through a symlink: {target}")
        os.replace(tmp, target)
    except (OSError, TypeError, ValueError):
        try:
            tmp.unlink()
        except OSError:
            pass
        raise


class MemoryStore:
    """The maintainer memory document plus its consistency gate.

    Build one with :meth:`load` to read an existing document, or directly with
    a path for a fresh store. :meth:`save` validates, logs one warning per
    issue and then atomically replaces the document.
    """

    def __init__(
        self, path: str | Path, *, clock: Callable[[], float] = time.time
    ) -> None:
        self._path = Path(path)
        self._clock = clock
        self._records: dict[str, list[Any]] = {name: [] for name in _COLLECTIONS}
        self._issues: list[str] = []
        # ``False`` once ``_read`` met a corrupt document: the empty records are
        # then a degraded view, not the truth, so exporting or importing over
        # them would destroy the bytes still on disk (UTILTOOLS-79).
        self._loaded_ok = True
        # Leaf-most lock in the engine's order (gate -> engine ``_lock`` ->
        # this), taken by every read/write of the in-memory document so
        # overlapping cycles cannot interleave a record append with a restore
        # (UTILTOOLS-77 REQ-05, INV-CONC-7).
        self._write_lock = threading.RLock()

    @classmethod
    def load(
        cls, path: str | Path, *, clock: Callable[[], float] = time.time
    ) -> "MemoryStore":
        """Read the document at ``path``; a missing file is an empty store.

        Only a regular file is read. Anything else - a directory, FIFO, device,
        symlink, oversized file or unparseable JSON - yields an empty store
        whose :meth:`issues` names the reason instead of raising.
        """

        store = cls(path, clock=clock)
        store._read()
        return store

    def _read(self) -> None:
        path = self._path
        try:
            info = os.lstat(path)
        except OSError:
            return  # absent document: empty store, no issue to report
        if not stat.S_ISREG(info.st_mode):
            self._loaded_ok = False
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is not a regular file"
            ]
            return
        if info.st_size > MEMORY_READ_LIMIT:
            self._loaded_ok = False
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is larger than "
                f"{MEMORY_READ_LIMIT} bytes"
            ]
            return
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                raw = handle.read(MEMORY_READ_LIMIT)
        except OSError as error:
            self._loaded_ok = False
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is unreadable "
                f"({type(error).__name__})"
            ]
            return
        try:
            document = json.loads(raw)
        except (ValueError, RecursionError) as error:
            self._loaded_ok = False
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is not valid JSON "
                f"({type(error).__name__})"
            ]
            return
        issues = validate_memory_records(document)
        if issues:
            self._loaded_ok = False
            self._issues = issues
            return
        for name in _COLLECTIONS:
            self._records[name] = list(document[name])

    def records(self) -> dict[str, Any]:
        """Defensive copy of the document this store would write."""

        document: dict[str, Any] = {"version": MEMORY_DOC_VERSION}
        with self._write_lock:
            for name in _COLLECTIONS:
                document[name] = [
                    dict(record) if isinstance(record, Mapping) else record
                    for record in self._records[name]
                ]
        return document

    def issues(self) -> list[str]:
        """The issues found by the last :meth:`save` / :meth:`load`."""

        return list(self._issues)

    def validate(
        self,
        *,
        max_runs: int | None = None,
        max_bug_fixes: int | None = None,
        max_learnings: int | None = None,
    ) -> list[str]:
        """Report every integrity problem with the document; ``[]`` when clean.

        Read-only: neither the in-memory records nor the file on disk change.
        A document that failed to load returns its recorded issues instead of
        ``[]`` ("unknown" is never "valid"), then runs ordering (REQ-03), the
        required fields of ``bug_fixes``/``learnings`` (REQ-03), the duplicate
        brief keys (REQ-04) and finally the optional count limits (REQ-06).
        """

        if not self._loaded_ok:
            return list(self._issues)
        limits = {
            "runs": max_runs,
            "bug_fixes": max_bug_fixes,
            "learnings": max_learnings,
        }
        with self._write_lock:
            snapshot = self.records()
        errors: list[str] = []
        for name in _COLLECTIONS:
            records = snapshot[name]
            if name == "runs":
                errors.extend(_validate_runs(records))
                continue
            errors.extend(
                _validate_required(name, records, _VALIDATE_REQUIRED_CODES[name])
            )
        for name in _COLLECTIONS:
            errors.extend(_validate_duplicates(name, snapshot[name]))
        for name in _COLLECTIONS:
            errors.extend(_validate_limits(name, snapshot[name], limits[name]))
        return errors

    def export_memory(self, path: str | Path | None = None) -> dict[str, Any]:
        """Return a restorable snapshot of this document, optionally written out.

        The mapping is the ``version 1`` envelope :meth:`records` emits, deep
        copied and with ``runs`` re-ordered ascending by ``at`` (newest last;
        a missing or non-numeric ``at`` sorts first, ties keep their order).
        The live store is untouched - the sort happens on the copy.

        With ``path`` the snapshot is written through :func:`atomic_write_text`
        using exactly the bytes :meth:`save` would persist, so an export is
        importable by construction. ``ValueError`` when the live document is
        corrupt (there is nothing trustworthy to export) or when ``path`` names
        that document; ``OSError`` from the write propagates untouched
        (UTILTOOLS-79 REQ-01).
        """

        if not self._loaded_ok:
            raise ValueError(_corrupt_label(self._issues))
        target = Path(path) if path is not None else None
        if target is not None:
            try:
                same = target.resolve() == self._path.resolve()
            except (OSError, RuntimeError):  # pragma: no cover - resolution only
                same = target == self._path
            if same:
                raise ValueError("export path is the live memory document")
        with self._write_lock:
            document = copy.deepcopy(self.records())
        document["runs"] = [
            record for _, record in sorted(enumerate(document["runs"]), key=_timestamp_order)
        ]
        if target is not None:
            atomic_write_text(
                target, json.dumps(document, indent=2, sort_keys=True) + "\n"
            )
        return document

    @classmethod
    def import_memory(
        cls,
        source: Mapping[str, Any] | str | Path,
        *,
        path: str | Path,
        clock: Callable[[], float] = time.time,
        max_runs: int | None = None,
        max_bug_fixes: int | None = None,
        max_learnings: int | None = None,
        known_task_ids: Collection[str] | None = None,
        persist: bool = True,
    ) -> list[str]:
        """Merge a snapshot (mapping or JSON file) into the document at ``path``.

        Oldest source record first, per collection: a record whose brief key
        (``task_id`` for runs, ``git_commit`` for bug fixes, lesson ``at`` for
        learnings) or validator identity key already exists is skipped, a record
        whose append would add a validator error is rolled back, then the merged
        document is pruned to the ``max_*`` limits and saved only when its bytes
        changed. Nothing is written when the target is corrupt or when the
        merged document would be less valid than the pre-import one.

        Returns one string per skip / prune / save issue; a hard failure is a
        single ``MEMORY_IMPORT_FAILED:`` / ``MEMORY_CORRUPT:`` entry, never an
        exception (UTILTOOLS-79 REQ-02, REQ-04).
        """

        if path is None:
            raise ValueError("import_memory: path is required")
        document, failures = _read_import_source(source)
        if document is None:
            return failures
        store = cls.load(path, clock=clock)
        if not store._loaded_ok:
            return [f"{_corrupt_label(store._issues)} (nothing imported)"]

        baseline = len(
            validate_memory_records(store.records(), known_task_ids=known_task_ids)
        )
        pre_import = store.records()
        issues: list[str] = []
        seen: dict[str, set[tuple[str, str]]] = {name: set() for name in _COLLECTIONS}
        for name in _COLLECTIONS:
            for existing in store.records()[name]:
                if isinstance(existing, Mapping):
                    seen[name] |= _dedupe_keys(name, existing)

        for name in _COLLECTIONS:
            for _, candidate in sorted(
                enumerate(document[name]), key=_timestamp_order
            ):
                if not isinstance(candidate, Mapping):
                    issues.append(
                        f"MEMORY_IMPORT_SKIPPED: would break {name} "
                        "(record is not a mapping)"
                    )
                    continue
                keys = _dedupe_keys(name, candidate)
                if _brief_keys(name, candidate) & seen[name]:
                    issues.append(
                        f"MEMORY_IMPORT_SKIPPED: duplicates {name} "
                        f"({_record_label(name, candidate)})"
                    )
                    continue
                if keys & seen[name]:
                    issues.append(
                        f"MEMORY_IMPORT_SKIPPED: would break {name} "
                        f"({_record_label(name, candidate)})"
                    )
                    continue
                before = len(_collection_errors(store, name))
                # Hold the lock across append + rollback: releasing it in
                # between would let a peer cycle append and then be popped by
                # position instead of the rejected candidate.
                with store._write_lock:
                    store._append(name, candidate)
                    rejected = len(_collection_errors(store, name)) > before
                    if rejected:
                        store._records[name].pop()
                if rejected:
                    issues.append(
                        f"MEMORY_IMPORT_SKIPPED: would break {name} "
                        f"({_record_label(name, candidate)})"
                    )
                    continue
                seen[name] |= keys

        dropped = store.prune(
            max_runs=max_runs,
            max_bug_fixes=max_bug_fixes,
            max_learnings=max_learnings,
        )
        for name in _COLLECTIONS:
            if dropped.get(name):
                issues.append(
                    f"MEMORY_IMPORT_PRUNED: {name} dropped {dropped[name]} record(s)"
                )

        errors = validate_memory_records(
            store.records(), known_task_ids=known_task_ids
        )
        if len(errors) > baseline:
            issues.append(
                "MEMORY_IMPORT_SKIPPED: would break document "
                f"({errors[0] if errors else 'unknown'})"
            )
            return issues
        if not persist or store.records() == pre_import:
            # Byte-identical merge: leave the document alone so a repeated
            # import cannot rewrite ``at`` stamps or reorder anything.
            return issues
        issues.extend(store.save(known_task_ids=known_task_ids))
        return issues

    def add_run(self, run: Mapping[str, Any], *, cycle: int | None = None) -> None:
        """Append one run record (stamped with ``at`` and ``cycle`` when absent)."""

        self._append("runs", run, cycle=cycle)

    def add_bug_fix(
        self, bug_fix: Mapping[str, Any], *, cycle: int | None = None
    ) -> None:
        """Append one bug-fix record (stamped with ``at``/``cycle`` when absent)."""

        self._append("bug_fixes", bug_fix, cycle=cycle)

    def add_learning(
        self, learning: Mapping[str, Any], *, cycle: int | None = None
    ) -> None:
        """Append one learning record (stamped with ``at``/``cycle`` when absent)."""

        self._append("learnings", learning, cycle=cycle)

    def _append(
        self, name: str, record: Mapping[str, Any], *, cycle: int | None = None
    ) -> None:
        stored = dict(record)
        stored.setdefault("at", self._clock())
        if cycle is not None:
            # A caller-supplied ``cycle`` always wins, so back-filled records
            # are never rewritten (UTILTOOLS-77 REQ-05).
            stored.setdefault("cycle", int(cycle))
        with self._write_lock:
            self._records[name].append(stored)

    def snapshot_records(self) -> dict[str, list[Any]]:
        """Deep copy of the three collections, safe to hold across cycles.

        The engine captures this before a cycle body runs and restores it after
        (UTILTOOLS-77 REQ-02). Copying under ``_write_lock`` means a peer
        cycle's append cannot land in the middle of the capture, and the copy is
        total (nested containers included) so a later mutation of the live
        records cannot rewrite the snapshot.
        """

        with self._write_lock:
            return {
                name: copy.deepcopy(list(self._records[name]))
                for name in _COLLECTIONS
            }

    def drop_records(self, *, cycle: int) -> int:
        """Remove every record stamped with ``cycle``; returns how many went.

        This is the cycle-scoped half of the snapshot restore: when peers are
        still running the engine must not roll the whole document back, so it
        deletes only the records its own cycle attributed to itself.
        """

        wanted = int(cycle)
        removed = 0
        with self._write_lock:
            for name in _COLLECTIONS:
                kept: list[Any] = []
                for record in self._records[name]:
                    if (
                        isinstance(record, Mapping)
                        and record.get("cycle") == wanted
                    ):
                        removed += 1
                        continue
                    kept.append(record)
                self._records[name] = kept
        return removed

    def restore_records(self, records: Mapping[str, Any]) -> None:
        """Replace the in-memory document from a snapshot; never touches disk.

        Only the three known collections are read, and each must be a list -
        anything else raises ``ValueError`` so a corrupt snapshot cannot
        silently blank the store. A *missing* collection is also an error
        rather than an implicit empty list: "unknown" (a failed capture) must
        never be indistinguishable from "was empty" (UTILTOOLS-77 v2 finding 2).
        The next :meth:`save` persists the result.
        """

        restored: dict[str, list[Any]] = {}
        for name in _COLLECTIONS:
            if name not in records:
                raise ValueError(f"restore_records: {name} is missing")
            value = records[name]
            if not isinstance(value, list):
                raise ValueError(f"restore_records: {name} is not a list")
            restored[name] = [
                dict(record) if isinstance(record, Mapping) else record
                for record in value
            ]
        with self._write_lock:
            self._records = restored

    def save(self, *, known_task_ids: Collection[str] | None = None) -> list[str]:
        """Validate then persist; returns the issues found (REQ-02)."""

        return self._save(known_task_ids=known_task_ids)

    def prune(
        self,
        *,
        max_runs: int | None = None,
        max_bug_fixes: int | None = None,
        max_learnings: int | None = None,
    ) -> dict[str, int]:
        """Trim each collection to its maximum, oldest record first (REQ-03).

        Returns ``{"runs": n, "bug_fixes": n, "learnings": n}`` with the number
        of records dropped from each collection. A ``None`` limit keeps that
        collection untouched; a negative limit is treated the same way (the
        caller's config already rejects values below 1). Nothing is written
        here - the next :meth:`save` persists the trimmed document.
        """

        limits = {
            "runs": max_runs,
            "bug_fixes": max_bug_fixes,
            "learnings": max_learnings,
        }
        dropped: dict[str, int] = {}
        for name in _COLLECTIONS:
            limit = limits[name]
            records = self._records[name]
            if limit is None or limit < 0:
                dropped[name] = 0
                continue
            excess = len(records) - int(limit)
            if excess > 0:
                # Records are appended oldest-first, so trimming the head keeps
                # the most recent ``limit`` entries.
                del records[:excess]
                dropped[name] = excess
            else:
                dropped[name] = 0
        return dropped

    def _save(self, *, known_task_ids: Collection[str] | None = None) -> list[str]:
        """Validate the records, log one warning per issue, write the document.

        Returns the issue list (``[]`` when clean) - issues never block the
        write, because losing the cycle's records is worse than persisting a
        document a human still has to fix. ``known_task_ids`` drives the
        orphaned-bug-fix check; ``None`` skips it.
        """

        with self._write_lock:
            document = self.records()
            errors = list(
                validate_memory_records(document, known_task_ids=known_task_ids)
            )
            self._issues = list(errors)
        for error in errors:
            LOG.warning("%s", error)
        try:
            self._write(document)
        except (OSError, TypeError, ValueError) as error:
            LOG.warning("memory document write failed: %s", type(error).__name__)
        return list(errors)

    def _write(self, document: Mapping[str, Any]) -> None:
        """Replace the document atomically (``.tmp`` sibling + ``os.replace``)."""

        payload = json.dumps(document, indent=2, sort_keys=True) + "\n"
        target = self._path
        tmp = target.with_name(target.name + ".tmp")
        if target.is_symlink():
            raise OSError("memory document is a symlink")
        flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0)
        handle_fd = os.open(tmp, flags, 0o644)
        try:
            with os.fdopen(handle_fd, "w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            if target.is_symlink():  # narrow the swap window
                raise OSError("memory document is a symlink")
            os.replace(tmp, target)
        except (OSError, TypeError, ValueError):
            try:
                tmp.unlink()
            except OSError:
                pass
            raise
