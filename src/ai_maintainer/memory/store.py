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
from typing import Any, Callable, Collection, Mapping

from ai_maintainer.consistency import validate_memory_records

__all__ = ["MEMORY_DOC_NAME", "MEMORY_DOC_VERSION", "MemoryStore"]

MEMORY_DOC_NAME = "maintainer_memory.json"
MEMORY_DOC_VERSION = 1
MEMORY_READ_LIMIT = 65536
LOG = logging.getLogger(__name__)

_COLLECTIONS = ("runs", "bug_fixes", "learnings")


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
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is not a regular file"
            ]
            return
        if info.st_size > MEMORY_READ_LIMIT:
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is larger than "
                f"{MEMORY_READ_LIMIT} bytes"
            ]
            return
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                raw = handle.read(MEMORY_READ_LIMIT)
        except OSError as error:
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is unreadable "
                f"({type(error).__name__})"
            ]
            return
        try:
            document = json.loads(raw)
        except (ValueError, RecursionError) as error:
            self._issues = [
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is not valid JSON "
                f"({type(error).__name__})"
            ]
            return
        issues = validate_memory_records(document)
        if issues:
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
        silently blank the store. The next :meth:`save` persists the result.
        """

        restored: dict[str, list[Any]] = {}
        for name in _COLLECTIONS:
            value = records.get(name, [])
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
