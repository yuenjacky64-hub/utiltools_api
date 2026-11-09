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

import json
import logging
import os
import stat
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
        for name in _COLLECTIONS:
            document[name] = [
                dict(record) if isinstance(record, Mapping) else record
                for record in self._records[name]
            ]
        return document

    def issues(self) -> list[str]:
        """The issues found by the last :meth:`save` / :meth:`load`."""

        return list(self._issues)

    def add_run(self, run: Mapping[str, Any]) -> None:
        """Append one run record (stamped with ``at`` when absent)."""

        self._append("runs", run)

    def add_bug_fix(self, bug_fix: Mapping[str, Any]) -> None:
        """Append one bug-fix record (stamped with ``at`` when absent)."""

        self._append("bug_fixes", bug_fix)

    def add_learning(self, learning: Mapping[str, Any]) -> None:
        """Append one learning record (stamped with ``at`` when absent)."""

        self._append("learnings", learning)

    def _append(self, name: str, record: Mapping[str, Any]) -> None:
        stored = dict(record)
        stored.setdefault("at", self._clock())
        self._records[name].append(stored)

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

        document = self.records()
        errors = list(validate_memory_records(document, known_task_ids=known_task_ids))
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
