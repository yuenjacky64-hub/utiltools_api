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

Cross-process safety (UTILTOOLS-98). The daemon and the CLI are independent
processes sharing this one document, so every mutation is a *locked
read-merge-write transaction*: :meth:`MemoryStore.save` takes
:func:`ai_maintainer.memory.locking.file_lock`, re-reads the document from
disk, merges only this process's unsynced records (keyed by ``entry_id``,
``INV-CP-4``), prunes, writes atomically, re-renders
``KNOWLEDGE_BASE.md`` inside the same lock and only then replaces the in-memory
state. A peer's full-snapshot write can therefore neither erase a durable
record nor resurrect one a peer pruned. ``entry_id`` is derived from the
record's canonical content (``INV-CP-3``): it is fixed the moment the record is
stamped, reproducible by every process, and needs no extra field in the
``version 1`` document (REQ-07).
"""

from __future__ import annotations

import copy
import hashlib
import json
import logging
import math
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
from ai_maintainer.memory.locking import LOCK_UNAVAILABLE, file_lock

__all__ = [
    "KNOWLEDGE_BASE_DOC_NAME",
    "MEMORY_KB_RENDER_FAILED",
    "MEMORY_DOC_NAME",
    "MEMORY_DOC_VERSION",
    "MEMORY_LOCK_UNAVAILABLE",
    "MemoryStore",
    "atomic_write_text",
]

MEMORY_DOC_NAME = "maintainer_memory.json"
MEMORY_DOC_VERSION = 1
MEMORY_READ_LIMIT = 65536

#: Human-readable view of the document, written beside it inside the save lock
#: so a reader never sees knowledge-base text that disagrees with the document
#: it was rendered from (UTILTOOLS-98 REQ-02, INV-CP-10).
KNOWLEDGE_BASE_DOC_NAME = "KNOWLEDGE_BASE.md"

#: Issue code for a knowledge-base render/write failure (REQ-04, INV-CP-5).
MEMORY_KB_RENDER_FAILED = "MEMORY_KB_RENDER_FAILED"

#: Issue code re-exported from :mod:`ai_maintainer.memory.locking` (INV-CP-1).
MEMORY_LOCK_UNAVAILABLE = LOCK_UNAVAILABLE

LOG = logging.getLogger(__name__)

_COLLECTIONS = ("runs", "bug_fixes", "learnings")

#: The field a caller may set to pin a record's merge key; otherwise the key is
#: derived from the record's content (INV-CP-3).
ENTRY_ID_FIELD = "entry_id"

#: Per-collection identifier plus human text used by the knowledge-base render.
_KB_ID_FIELD = {"runs": "run_id", "bug_fixes": "fix_id", "learnings": "learning_id"}
_KB_TEXT_FIELDS = {
    "runs": ("state", "task_id"),
    "bug_fixes": ("summary", "component"),
    "learnings": ("text",),
}
_KB_SECTION_TITLES = {
    "learnings": "Learnings",
    "bug_fixes": "Bug fixes",
    "runs": "Runs",
}
_KB_TITLE = "# Maintainer knowledge base"

#: How many ``<name>.corrupt-<stamp>[-n]`` names a quarantine may try before it
#: gives up and leaves the document in place (UTILTOOLS-82).
_QUARANTINE_ATTEMPTS = 10

#: Fields an import uses to recognise a record it has already seen. The first
#: field of each tuple is the brief key of UTILTOOLS-79 REQ-04; the remaining
#: ones are the validators' identity keys, which must also be unique or the
#: next ``_load`` would blank the store.
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


def _structure_issues(document: Any) -> list[str]:
    """One ``MEMORY_CORRUPT`` issue per collection key that is missing/not a list.

    Only the envelope is inspected: a non-mapping root is left to
    :func:`validate_memory_records`, and the values themselves are never
    iterated, counted or coerced, so a huge or exotic payload cannot make the
    predicate expensive or raise (UTILTOOLS-82 REQ-01/REQ-02/REQ-03).
    """

    if not isinstance(document, Mapping):
        return []  # root shape stays with validate_memory_records
    issues: list[str] = []
    for name in _COLLECTIONS:
        if name not in document:
            issues.append(
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is missing the '{name}' list"
            )
        elif not isinstance(document[name], list):
            issues.append(
                f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} field '{name}' must be a list"
                f" (got {type(document[name]).__name__})"
            )
    return issues


def _empty_document() -> dict[str, Any]:
    """A well-formed document with no records."""

    document: dict[str, Any] = {"version": MEMORY_DOC_VERSION}
    for name in _COLLECTIONS:
        document[name] = []
    return document


def _fingerprint(path: str | Path) -> tuple[int, int, int, int] | None:
    """Cheap identity of the file on disk: replace changes inode and mtime.

    ``None`` when nothing is there. Used by the read paths to notice a peer's
    write without re-reading (REQ-06) and by the save transaction to record
    what in-memory state agrees with (INV-CP-8).
    """

    try:
        info = os.lstat(path)
    except OSError:
        return None
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)


def _entry_id(name: str, record: Any) -> str:
    """Stable merge key for one record (REQ-01, INV-CP-3).

    A caller-supplied non-blank ``entry_id`` always wins (the import path may
    hand over ids minted elsewhere). Otherwise the id is
    ``e-`` plus the first 24 hex digits of a SHA-256 over the collection name
    and the record's canonical JSON, so it is
    *reproducible by every process* and *fixed the moment the record is
    stamped* - a retry or a peer re-deriving it gets the same value, which is
    what makes the merge exactly-once. The collection name is inside the
    digest, so ids stay unique across collections.
    """

    if isinstance(record, Mapping):
        supplied = record.get(ENTRY_ID_FIELD)
        if isinstance(supplied, str) and supplied.strip():
            return supplied.strip()
    payload = json.dumps(
        record, sort_keys=True, separators=(",", ":"), ensure_ascii=True, default=repr
    )
    digest = hashlib.sha256(f"{name}\x00{payload}".encode("utf-8")).hexdigest()
    return f"e-{digest[:24]}"


def _entry_ids(name: str, records: Sequence[Any]) -> list[str]:
    """``_entry_id`` per record, disambiguating repeated content in order.

    Two records with byte-identical content in one collection would otherwise
    share a key; they are given ``-2``, ``-3``, ... suffixes in document order,
    which is stable across loads because the order is.
    """

    seen: dict[str, int] = {}
    ids: list[str] = []
    for record in records:
        base = _entry_id(name, record)
        count = seen.get(base, 0) + 1
        seen[base] = count
        ids.append(base if count == 1 else f"{base}-{count}")
    return ids


def _prune_document(
    document: dict[str, Any], limits: Mapping[str, int | None]
) -> dict[str, int]:
    """Drop the oldest records over ``limits`` in place; return the counts.

    Same rule as :meth:`MemoryStore.prune`: the collections are kept in
    ascending ``at`` order, so the survivors are the newest ``limit`` entries.
    """

    dropped: dict[str, int] = {}
    for name in _COLLECTIONS:
        limit = limits.get(name)
        records = document.get(name)
        if limit is None or limit < 0 or not isinstance(records, list):
            dropped[name] = 0
            continue
        excess = len(records) - int(limit)
        if excess <= 0:
            dropped[name] = 0
            continue
        del records[:excess]
        dropped[name] = excess
    return dropped


def _one_line(value: Any) -> str:
    """``value`` as a single trimmed line of text; ``''`` when it is not text."""

    if not isinstance(value, str):
        return ""
    return " ".join(value.split())


def _recent_order(item: tuple[int, Any]) -> tuple[float, int]:
    """Newest-first sort key for a ``(index, record)`` pair.

    A record without a usable ``at`` sorts last instead of first (the opposite
    of ``_timestamp_order``): "recent" means the newest timestamps lead. The
    index keeps ties stable in document order.
    """

    index, record = item
    number = _finite_timestamp(record.get("at") if isinstance(record, Mapping) else None)
    return (-number if number is not None else math.inf, index)


def _kb_bullet(name: str, record: Any) -> str | None:
    """One deterministic Markdown bullet for ``record``; ``None`` if unusable."""

    if not isinstance(record, Mapping):
        return None
    identifier = _one_line(record.get(_KB_ID_FIELD[name]))
    task = _one_line(record.get("task_id"))
    label = identifier or task or "-"
    text = ""
    for field in _KB_TEXT_FIELDS[name]:
        candidate = _one_line(record.get(field))
        if candidate:
            text = candidate
            break
    line = f"- {label}"
    if text:
        line += f": {text}"
    if task and task != label:
        line += f" (task {task})"
    return line


def _knowledge_base_text(
    document: Mapping[str, Any], *, limit: int | None = None
) -> str:
    """Render the knowledge base (REQ-02, INV-CP-10).

    Deterministic by construction: stable section order, document order inside
    a section, one bullet per recognisable record, and only fixed fields - so
    the file is byte-identical whenever the document is. ``limit`` caps each
    section and selects the *newest* records, which is what the prompt context
    of REQ-06 wants.
    """

    lines = [_KB_TITLE, ""]
    for name in ("learnings", "bug_fixes", "runs"):
        records = document.get(name)
        if not isinstance(records, list):
            records = []
        ordered = list(enumerate(records))
        ordered.sort(key=_timestamp_order)
        if limit is not None and limit >= 0:
            ordered = ordered[-int(limit):] if int(limit) else []
        bullets = [
            bullet
            for _, record in ordered
            if (bullet := _kb_bullet(name, record)) is not None
        ]
        lines.append(f"## {_KB_SECTION_TITLES[name]}")
        lines.append("")
        lines.extend(bullets or ["- none recorded"])
        lines.append("")
    return "\n".join(lines).rstrip("\n") + "\n"


def _finite_timestamp(value: Any) -> float | None:
    """``value`` as a finite ``float``, or ``None`` when it is not one.

    JSON admits integers far beyond ``float``'s range (``float()`` raises
    ``OverflowError``) and the non-finite literals ``NaN``/``Infinity``
    (``math.isfinite`` is ``False``). Both are unplaceable in time, so they are
    treated as a missing timestamp instead of escaping as an exception: every
    helper over an untrusted record stays total (INV-VAL-1).
    """

    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        number = float(value)
    except (OverflowError, ValueError, TypeError):  # oversized int / exotic float
        return None
    if not math.isfinite(number):
        return None
    return number


def _timestamp_order(item: tuple[int, Any]) -> tuple[int, float, int]:
    """Sort key for a ``(index, record)`` pair: ascending ``at``, newest last.

    A record whose ``at`` is missing, not a real number, or not a finite
    ``float`` sorts before every orderable one; the index keeps the sort stable
    so the live order is never rewritten by a tie.
    """

    index, record = item
    value = record.get("at") if isinstance(record, Mapping) else None
    number = _finite_timestamp(value)
    if number is None:
        return (0, 0.0, index)
    return (1, number, index)


def _dedupe_key(kind: str, value: Any) -> str | None:
    """Normalise one dedupe field; ``None`` when the record does not carry it."""

    if kind == "timestamp":
        number = _finite_timestamp(value)
        return None if number is None else repr(number)
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
                # Plan §4.1 step 6 pins a bare timestamp (``12.0``) but a
                # quoted string key (``'T1'``).
                rendered = key if kind == "timestamp" else repr(key)
                errors.append(
                    f"{code}: {name}[{index}].{field_name} {rendered} repeats "
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
        # ``False`` once ``_load`` met a corrupt document: the empty records are
        # then a degraded view, not the truth, so exporting or importing over
        # them would destroy the bytes still on disk (UTILTOOLS-79).
        self._loaded_ok = True
        # Where a structural failure moved the document, if it fired at all
        # (UTILTOOLS-82); ``None`` until then and after a failed rename.
        self._quarantined_path: Path | None = None
        # Records this process appended but has not yet merged into the
        # document on disk (REQ-03). The save transaction merges *these* - and
        # only these - so a peer's entries survive and a record a peer pruned
        # is not resurrected from a stale snapshot.
        self._pending: dict[str, list[Any]] = {name: [] for name in _COLLECTIONS}
        # ``entry_id`` of records this process dropped on purpose (``prune`` /
        # ``drop_records``). They are filtered out of the merged document even
        # though the disk snapshot we re-read still carries them.
        self._dropped: dict[str, set[str]] = {name: set() for name in _COLLECTIONS}
        # Fingerprint of the disk state the in-memory collections agree with
        # (INV-CP-8): a read path refreshes when the file moved on.
        self._last_sync: tuple[int, int, int, int] | None = None
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
        store._load()
        return store

    def _read_document(self) -> tuple[dict[str, Any] | None, list[str], str]:
        """Read and parse the document on disk; total, never raises.

        Returns ``(document, issues, kind)`` where ``kind`` is ``absent`` (no
        file: an empty document, no issue), ``shape`` / ``json`` (the bytes are
        not a document at all: ``document is None`` and the caller may
        quarantine), or ``records`` / ``ok`` (the envelope is sound;
        ``issues`` then holds the record-level findings of
        :func:`validate_memory_records`). One reader for load, merge and
        refresh, so all three see the same document under the same rules
        (INV-CP-2).
        """

        path = self._path
        try:
            info = os.lstat(path)
        except OSError:
            return _empty_document(), [], "absent"
        if not stat.S_ISREG(info.st_mode):
            return (
                None,
                [f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is not a regular file"],
                "shape",
            )
        if info.st_size > MEMORY_READ_LIMIT:
            return (
                None,
                [
                    f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is larger than "
                    f"{MEMORY_READ_LIMIT} bytes"
                ],
                "shape",
            )
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                raw = handle.read(MEMORY_READ_LIMIT)
        except OSError as error:
            return (
                None,
                [
                    f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is unreadable "
                    f"({type(error).__name__})"
                ],
                "shape",
            )
        try:
            document = json.loads(raw)
        except (ValueError, RecursionError) as error:
            return (
                None,
                [
                    f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} is not valid JSON "
                    f"({type(error).__name__})"
                ],
                "json",
            )
        structure = _structure_issues(document)
        if structure:
            return None, structure, "shape"
        return document, validate_memory_records(document), "records"

    def _load(self) -> None:
        document, issues, kind = self._read_document()
        self._last_sync = _fingerprint(self._path)
        if kind == "absent":
            return  # absent document: empty store, no issue to report
        if document is None or issues:
            self._loaded_ok = False
            self._issues = list(issues)
            if kind == "shape":
                self._quarantine_corrupt_file()
                self._last_sync = _fingerprint(self._path)
            return
        for name in _COLLECTIONS:
            self._records[name] = list(document[name])

    def _quarantine_corrupt_file(self) -> Path | None:
        """Move a structurally corrupt document aside; ``None`` when it cannot.

        The bytes are preserved under ``<name>.corrupt-<stamp>[-n]`` beside the
        document so a later ``save()`` can start a fresh file without destroying
        the evidence. Every failure - no file, a non-regular file, a read-only
        directory - is logged or silently skipped instead of raised: the load
        path must never fail because the quarantine could not be performed
        (UTILTOOLS-82, INV-STR-1).
        """

        if self._quarantined_path is not None:
            return self._quarantined_path
        with file_lock(self._path):
            with self._write_lock:
                return self._quarantine_locked()

    def _quarantine_locked(self) -> Path | None:
        """Quarantine decision taken while holding the document lock.

        Two loaders that both met the same corrupt bytes would otherwise each
        rename it (one of them renaming the file the other just wrote) and
        leave two backups. The lock makes the decision single-winner: whoever
        gets here first renames, and everyone after re-reads the document,
        finds it absent or valid - ``kind`` is no longer ``shape`` - and skips
        (REQ-05, INV-CP-9).
        """

        document, _issues, kind = self._read_document()
        if kind != "shape":
            return None
        try:
            info = os.lstat(self._path)
        except OSError:
            return None
        if not stat.S_ISREG(info.st_mode):
            return None  # never rename a link, FIFO, directory or device
        try:
            stamp = str(int(self._clock()))
        except (TypeError, ValueError, OverflowError):
            stamp = "0"
        parent = self._path.parent
        name = self._path.name
        candidates = [parent / f"{name}.corrupt-{stamp}"]
        candidates += [
            parent / f"{name}.corrupt-{stamp}-{index}"
            for index in range(1, _QUARANTINE_ATTEMPTS + 1)
        ]
        for candidate in candidates:
            try:
                os.close(
                    os.open(
                        candidate,
                        os.O_CREAT | os.O_EXCL | os.O_WRONLY,
                        0o600,
                    )
                )
            except FileExistsError:
                continue
            except OSError as error:
                LOG.warning(
                    "memory document quarantine unavailable: %s (%s)",
                    candidate,
                    type(error).__name__,
                )
                return None
            try:
                os.replace(self._path, candidate)
            except OSError as error:
                try:
                    candidate.unlink()
                except OSError:
                    pass
                LOG.warning(
                    "memory document quarantine failed: %s -> %s (%s)",
                    self._path,
                    candidate,
                    type(error).__name__,
                )
                return None
            try:  # the rename carries the document's own mode; tighten it
                os.chmod(candidate, 0o600)
            except OSError:
                pass
            LOG.warning("memory document quarantined: %s -> %s", self._path, candidate)
            self._quarantined_path = candidate
            return candidate
        LOG.warning(
            "memory document quarantine skipped: no free name for %s",
            self._path,
        )
        return None

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

    def entry_ids(self, collection: str) -> list[str]:
        """The merge key of every record in ``collection``, in document order.

        The keys the save transaction merges on (REQ-01): stable for a given
        record, identical in every process, and unique inside a collection.
        Exposed so callers and tests can assert exactly-once persistence
        without knowing how the key is derived.
        """

        if collection not in _COLLECTIONS:
            raise ValueError(f"unknown collection: {collection}")
        with self._write_lock:
            return _entry_ids(collection, list(self._records[collection]))

    def pending_count(self) -> int:
        """How many appended records have not reached the document yet."""

        with self._write_lock:
            return sum(len(records) for records in self._pending.values())

    def kb_path(self) -> Path:
        """Where :meth:`save` renders the knowledge base (``INV-CP-10``)."""

        return self._path.with_name(KNOWLEDGE_BASE_DOC_NAME)

    def refresh_from_disk(self) -> bool:
        """Adopt a peer's document when the file changed since the last sync.

        Returns ``True`` when the in-memory collections were replaced. The
        fingerprint check keeps the common case free of I/O and of the OS lock
        - a reader never blocks a writer (REQ-06, ``INV-CP-8``). Records this
        process has not saved yet are kept in the refreshed view, so ``records()``
        never forgets an append that is still pending.
        """

        with self._write_lock:
            fingerprint = _fingerprint(self._path)
            if fingerprint == self._last_sync:
                return False
            document, issues, _kind = self._read_document()
            self._last_sync = fingerprint
            if document is None or issues:
                self._issues = list(issues)
                return False
            refreshed: dict[str, list[Any]] = {}
            for name in _COLLECTIONS:
                records = [copy.deepcopy(record) for record in document[name]]
                present = {
                    _entry_id(name, record)
                    for record in records
                    if isinstance(record, Mapping)
                }
                for record in self._pending.get(name, ()):
                    key = _entry_id(name, record)
                    if key in present:
                        continue
                    records.append(dict(record) if isinstance(record, Mapping) else record)
                    present.add(key)
                refreshed[name] = records
            self._records = refreshed
            self._loaded_ok = True
            return True

    def get_context_for_prompt(self, *, limit: int = 10) -> str:
        """Knowledge-base text for the prompt, refreshed from disk first.

        Same three sections as the rendered file (``INV-CP-10``), capped at
        ``limit`` newest records each. Empty string when there is nothing to
        say, which is what callers already branch on (REQ-06, ``INV-CP-11``).
        """

        self.refresh_from_disk()
        with self._write_lock:
            snapshot = {name: list(self._records[name]) for name in _COLLECTIONS}
        if not any(snapshot[name] for name in _COLLECTIONS):
            return ""
        return _knowledge_base_text(snapshot, limit=limit)

    def recent_task_names(self, *, limit: int = 10) -> list[str]:
        """Distinct ``task_id`` of the newest runs, newest first, refreshed.

        Lets the proposal path see task names another process recorded without
        constructing a new store (REQ-06).
        """

        if limit is not None and int(limit) <= 0:
            return []
        self.refresh_from_disk()
        with self._write_lock:
            runs = list(self._records["runs"])
        candidates = [item for item in enumerate(runs) if isinstance(item[1], Mapping)]
        candidates.sort(key=_recent_order)
        names: list[str] = []
        seen: set[str] = set()
        for _index, record in candidates:
            name = _one_line(record.get("task_id"))
            if not name or name in seen:
                continue
            seen.add(name)
            names.append(name)
            if limit is not None and len(names) >= int(limit):
                break
        return names

    def quarantined_path(self) -> str | None:
        """Where a structural failure moved the document, ``None`` otherwise.

        Read-only and lock-free: the value is set at most once, inside the
        single-threaded :meth:`load` (UTILTOOLS-82).
        """

        if self._quarantined_path is None:
            return None
        return str(self._quarantined_path)

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
                        # The rejected candidate is not a record to persist, so
                        # it leaves the pending set with it (REQ-04).
                        if store._pending.get(name):
                            store._pending[name].pop()
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

    def record_run(self, run: Mapping[str, Any], *, cycle: int | None = None) -> None:
        """Alias of :meth:`add_run` - the durable record call of REQ-01."""

        self.add_run(run, cycle=cycle)

    def record_bug_fix(
        self, bug_fix: Mapping[str, Any], *, cycle: int | None = None
    ) -> None:
        """Alias of :meth:`add_bug_fix` - the durable record call of REQ-01."""

        self.add_bug_fix(bug_fix, cycle=cycle)

    def record_learning(
        self, learning: Mapping[str, Any], *, cycle: int | None = None
    ) -> None:
        """Alias of :meth:`add_learning` - the durable record call of REQ-01."""

        self.add_learning(learning, cycle=cycle)

    def _append(
        self, name: str, record: Mapping[str, Any], *, cycle: int | None = None
    ) -> None:
        stored = dict(record)
        stored.setdefault("at", self._clock())
        if cycle is not None:
            # A caller-supplied ``cycle`` always wins, so back-filled records
            # are never rewritten (UTILTOOLS-77 REQ-05).
            stored.setdefault("cycle", int(cycle))
        # Stamping above is what makes the record final, and the merge key of
        # REQ-01 is derived from exactly this content (``_entry_id``): the id
        # exists from this moment on, needs no field of its own in the
        # ``version 1`` document (REQ-07) and is recomputed identically by any
        # process that meets the record, so a retry cannot duplicate it.
        with self._write_lock:
            self._records[name].append(stored)
            self._pending[name].append(stored)

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
                gone: set[str] = set()
                for record in self._records[name]:
                    if (
                        isinstance(record, Mapping)
                        and record.get("cycle") == wanted
                    ):
                        removed += 1
                        gone.add(_entry_id(name, record))
                        continue
                    kept.append(record)
                self._records[name] = kept
                # A dropped record must not be written by the next save, so it
                # leaves the pending set with it (REQ-03, INV-CP-6).
                self._forget_pending(name, gone)
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
            for name in _COLLECTIONS:
                kept_keys = {
                    _entry_id(name, record)
                    for record in restored[name]
                    if isinstance(record, Mapping)
                }
                # Rollback discards this cycle's unsynced appends; a record a
                # peer already persisted is not in this process's pending set
                # and therefore survives the restore (REQ-03, T-5).
                self._pending[name] = [
                    record
                    for record in self._pending[name]
                    if _entry_id(name, record) in kept_keys
                ]

    def _forget_pending(self, name: str, keys: set[str]) -> None:
        """Drop the pending records of ``name`` whose ``entry_id`` is in ``keys``."""

        if not keys:
            return
        self._pending[name] = [
            record
            for record in self._pending.get(name, ())
            if _entry_id(name, record) not in keys
        ]

    def save(
        self,
        *,
        known_task_ids: Collection[str] | None = None,
        max_runs: int | None = None,
        max_bug_fixes: int | None = None,
        max_learnings: int | None = None,
    ) -> list[str]:
        """Persist the unsynced records in one locked transaction.

        Returns the issues found (``[]`` when clean). The extra ``max_*``
        keywords trim the *merged* document before it is written - the engine
        prunes the store and then saves, and either order has to end with a
        document inside the configured maxima (REQ-03).
        """

        return self._save(
            known_task_ids=known_task_ids,
            max_runs=max_runs,
            max_bug_fixes=max_bug_fixes,
            max_learnings=max_learnings,
        )

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
        with self._write_lock:
            for name in _COLLECTIONS:
                limit = limits[name]
                records = self._records[name]
                if limit is None or limit < 0:
                    dropped[name] = 0
                    continue
                excess = len(records) - int(limit)
                if excess <= 0:
                    dropped[name] = 0
                    continue
                # Records are appended oldest-first, so trimming the head keeps
                # the most recent ``limit`` entries.
                removed = records[:excess]
                del records[:excess]
                dropped[name] = excess
                # The disk snapshot the next save re-reads still contains these
                # records, so remember that they were dropped on purpose: a
                # trim must not be undone by the very merge that persists it
                # (REQ-03, INV-CP-6).
                gone = {
                    _entry_id(name, record)
                    for record in removed
                    if isinstance(record, Mapping)
                }
                self._dropped[name] |= gone
                self._forget_pending(name, gone)
        return dropped

    def _save(
        self,
        *,
        known_task_ids: Collection[str] | None = None,
        max_runs: int | None = None,
        max_bug_fixes: int | None = None,
        max_learnings: int | None = None,
    ) -> list[str]:
        """Persist the unsynced records as one cross-process transaction.

        Sequence (REQ-02, ``INV-CP-4``), all inside a single
        ``file_lock(self._path)`` scope and the store's ``_write_lock``:
        re-read the document from disk, merge *this process's pending records*
        into it by ``entry_id``, prune the merged document to the ``max_*``
        limits, validate it for the returned issue list, write it atomically,
        regenerate ``KNOWLEDGE_BASE.md`` from the same merged document, and only
        then adopt that document as the in-memory state.

        Returns the issue list (``[]`` when clean) - record-level issues never
        block the write, because losing the cycle's records is worse than
        persisting a document a human still has to fix. ``known_task_ids``
        drives the orphaned-bug-fix check; ``None`` skips it. A failed write or
        knowledge-base render keeps the records pending so a later save retries
        them exactly once (``INV-CP-5``); the method returns rather than raises,
        as its callers expect.
        """

        limits = {
            "runs": max_runs,
            "bug_fixes": max_bug_fixes,
            "learnings": max_learnings,
        }
        try:
            with file_lock(self._path):
                return self._save_transaction(
                    known_task_ids=known_task_ids, limits=limits
                )
        except TimeoutError as error:
            # The section was not serialised, so it is not a transaction: keep
            # the pending records for the next attempt (INV-CP-5).
            LOG.warning("memory document save deferred: %s", error)
            with self._write_lock:
                self._issues = [f"{MEMORY_LOCK_UNAVAILABLE}: save deferred"]
                return list(self._issues)

    def _save_transaction(
        self,
        *,
        known_task_ids: Collection[str] | None,
        limits: Mapping[str, int | None],
    ) -> list[str]:
        """Body of :meth:`_save`; the caller holds the cross-process lock."""

        with self._write_lock:
            base, base_issues = self._merge_base()
            if base is None:
                # A peer's unparseable bytes are not ours to overwrite, and the
                # pending records must survive for a later attempt (INV-CP-2).
                # The refusal is reported through ``issues()`` only: a caller
                # that cannot act on it must not mistake it for a bad record.
                self._issues = list(base_issues)
                return []
            merged = self._merge_pending(base)
            _prune_document(merged, limits)
            errors = list(
                validate_memory_records(merged, known_task_ids=known_task_ids)
            )
            try:
                self._write(merged)
            except (OSError, TypeError, ValueError) as error:
                LOG.warning(
                    "memory document write failed: %s", type(error).__name__
                )
                self._issues = list(errors)
                return list(errors)
            try:
                atomic_write_text(
                    self.kb_path(), self._render_knowledge_base(merged)
                )
            except (OSError, TypeError, ValueError) as error:
                # The document is complete on disk; only the rendering failed.
                # Keep pending so the retry replays it - the merge skips ids
                # already present, so nothing is duplicated (INV-CP-5).
                LOG.warning(
                    "%s: %s", MEMORY_KB_RENDER_FAILED, type(error).__name__
                )
                errors.append(f"{MEMORY_KB_RENDER_FAILED}: {type(error).__name__}")
                self._issues = list(errors)
                return list(errors)
            # Committed: the merged snapshot replaces the (possibly stale) local
            # one, so a peer's pruned record cannot come back from memory.
            self._records = {
                name: [copy.deepcopy(record) for record in merged[name]]
                for name in _COLLECTIONS
            }
            self._pending = {name: [] for name in _COLLECTIONS}
            self._dropped = {name: set() for name in _COLLECTIONS}
            self._last_sync = _fingerprint(self._path)
            self._loaded_ok = True
            self._issues = list(errors)
        for error in errors:
            LOG.warning("%s", error)
        return list(errors)

    def _merge_base(self) -> tuple[dict[str, Any] | None, list[str]]:
        """Re-read the document on disk as the base of the merge (INV-CP-2).

        Total, never raises. ``(document, [])`` for a valid document, a missing
        one (an empty document) or one quarantined a moment ago; ``(None,
        issues)`` when bytes are there but unusable - the caller then leaves the
        file alone instead of overwriting a peer's document with a degraded
        view. ``self._issues`` is not touched: the caller owns that field.
        """

        document, issues, kind = self._read_document()
        if kind == "absent":
            return _empty_document(), []
        if document is None or issues:
            if kind == "shape":
                # Same single-winner decision as the load path (REQ-05): if this
                # process wins it the file is gone, so the merge starts empty.
                self._quarantine_locked()
                document, issues, kind = self._read_document()
                if kind == "absent":
                    return _empty_document(), []
                if document is not None and not issues:
                    return document, []
            return None, list(issues)
        return document, []

    def _merge_pending(self, base: Mapping[str, Any]) -> dict[str, Any]:
        """``base`` plus this process's unsynced records, pruned ids excluded.

        Only the pending set is unioned - never the whole in-memory list - so a
        peer's entries survive and an entry a peer pruned is not resurrected
        from a stale snapshot (REQ-03, ``INV-CP-4``). Records already on disk
        are kept as they are (bytes and document order preserved), and a
        pending record whose ``entry_id`` is already there is skipped, which is
        what makes a replay exactly-once (REQ-04).
        """

        merged: dict[str, Any] = {"version": MEMORY_DOC_VERSION}
        for name in _COLLECTIONS:
            records = base.get(name)
            stored = list(records) if isinstance(records, list) else []
            gone = self._dropped.get(name) or set()
            if gone:
                stored = [
                    record
                    for record in stored
                    if not (
                        isinstance(record, Mapping)
                        and _entry_id(name, record) in gone
                    )
                ]
            kept = [copy.deepcopy(record) for record in stored]
            present = {
                _entry_id(name, record)
                for record in kept
                if isinstance(record, Mapping)
            }
            for record in self._pending.get(name, ()):
                stored_record = (
                    copy.deepcopy(dict(record))
                    if isinstance(record, Mapping)
                    else copy.deepcopy(record)
                )
                key = _entry_id(name, stored_record)
                if key in present:
                    continue
                present.add(key)
                kept.append(stored_record)
            merged[name] = kept
        return merged

    def _render_knowledge_base(self, document: Mapping[str, Any]) -> str:
        """Deterministic Markdown for ``document`` (REQ-02, ``INV-CP-10``)."""

        return _knowledge_base_text(document)

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
