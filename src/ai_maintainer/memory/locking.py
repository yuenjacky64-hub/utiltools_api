"""Cross-process advisory lock for the maintainer memory document (UTILTOOLS-98).

The engine (``MaintainerEngine``) and the CLI are two independently started
processes that read and write the *same* ``maintainer_memory.json``. An
in-process ``threading.RLock`` cannot see the other process, so every mutation
of the document is wrapped in an OS-level advisory lock taken on a stable
sibling file.

``INV-CP-1`` (lock target)
    ``file_lock(target)`` locks ``.<document name>.lock`` beside the document -
    never the document itself. Every write is an ``os.replace`` (see
    :meth:`ai_maintainer.memory.store.MemoryStore._write`), so the document's
    inode changes under the holder and a lock on it would guard nothing. The
    lock file is created ``0o600`` and is *never* unlinked: removing it races a
    waiter onto a different inode and splits the lock in two.

``INV-CP-7`` (nesting)
    ``file_lock`` is the outer lock; the store's ``_write_lock`` is taken
    inside it. Never the reverse.

``MEMORY_LOCK_UNAVAILABLE``
    A filesystem without ``flock`` (or a lock path that cannot be opened, e.g. a
    planted symlink) degrades to "no OS lock" with one warning per path instead
    of raising: memory must never become a precondition for a maintainer cycle.
    The caller still holds its thread lock, so the documented residual risk is
    exactly the pre-UTILTOOLS-98 interleaving.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

__all__ = ["LOCK_SUFFIX", "LOCK_UNAVAILABLE", "file_lock", "lock_path"]

try:  # POSIX
    import fcntl
except ImportError:  # pragma: no cover - platform dependent
    fcntl = None  # type: ignore[assignment]

try:  # Windows
    import msvcrt
except ImportError:  # pragma: no cover - platform dependent
    msvcrt = None  # type: ignore[assignment]

LOG = logging.getLogger(__name__)

#: Suffix appended to the document path to name its lock file.
LOCK_SUFFIX = ".lock"

#: Issue/log code emitted when the OS lock cannot be taken (verbatim, tests use it).
LOCK_UNAVAILABLE = "MEMORY_LOCK_UNAVAILABLE"

#: How long a non-blocking acquisition sleeps between attempts.
_POLL_INTERVAL = 0.01

_LOG_LOCK = threading.Lock()
_REPORTED: set[str] = set()
_HELD = threading.local()


def lock_path(target: str | Path) -> Path:
    """The sibling file ``file_lock`` locks for ``target``.

    The name is dot-prefixed so the lock file never passes for a document
    sibling: the store's own readers enumerate ``<name>*`` beside the document
    (quarantine candidates, ``*.tmp`` leftovers) and a visible lock file would
    read as debris.
    """

    path = Path(target)
    return path.with_name(f".{path.name}{LOCK_SUFFIX}")


def _report_once(path: Path, reason: str) -> None:
    """Log ``MEMORY_LOCK_UNAVAILABLE`` once per (path, reason) pair."""

    key = f"{path}\x00{reason}"
    with _LOG_LOCK:
        if key in _REPORTED:
            return
        _REPORTED.add(key)
    LOG.warning("%s: %s (%s)", LOCK_UNAVAILABLE, path, reason)


def _held_stack() -> list[Path]:
    stack = getattr(_HELD, "stack", None)
    if stack is None:
        stack = []
        _HELD.stack = stack
    return stack


def _acquire(fd: int, timeout: float | None) -> None:
    """Take the exclusive lock on ``fd``; ``OSError`` when no primitive exists."""

    if fcntl is not None:
        if timeout is None:
            fcntl.flock(fd, fcntl.LOCK_EX)
            return
        deadline = time.monotonic() + float(timeout)
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                return
            except OSError:
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"could not acquire {LOCK_SUFFIX} within {timeout}s"
                    )
                time.sleep(_POLL_INTERVAL)
    if msvcrt is not None:  # pragma: no cover - Windows only
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_LOCK, 1)
        return
    raise OSError("no file locking primitive on this platform")


def _release(fd: int) -> None:
    """Release the lock held on ``fd``; failures are logged, never raised."""

    try:
        if fcntl is not None:
            fcntl.flock(fd, fcntl.LOCK_UN)
        elif msvcrt is not None:  # pragma: no cover - Windows only
            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
    except OSError as error:  # pragma: no cover - defensive
        LOG.warning("%s: release failed (%s)", LOCK_UNAVAILABLE, type(error).__name__)


@contextmanager
def file_lock(target: str | Path, *, timeout: float | None = None) -> Iterator[None]:
    """Hold the exclusive cross-process lock for the document at ``target``.

    Re-entrant for the *same thread* on the same target (the inner block is a
    no-op): the save transaction takes the lock and may call a helper that
    takes it again, and a second ``flock`` on a fresh descriptor from one
    process would deadlock against itself.

    ``OSError`` from opening or locking the lock file is reported once and
    swallowed - the block runs without the OS lock (``INV-CP-7`` degradation).
    A ``timeout`` that expires is *not* swallowed: the caller must not treat an
    unserialised section as a transaction.
    """

    path = lock_path(target)
    stack = _held_stack()
    if stack and stack[-1] == path:  # same thread, same document: re-entrant
        stack.append(path)
        try:
            yield
        finally:
            stack.pop()
        return

    fd: int | None = None
    flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags, 0o600)
    except OSError as error:
        _report_once(path, type(error).__name__)
    if fd is not None:
        try:
            _acquire(fd, timeout)
        except OSError as error:
            if isinstance(error, TimeoutError):
                os.close(fd)
                raise
            _report_once(path, type(error).__name__)
            os.close(fd)
            fd = None
    stack.append(path)
    try:
        yield
    finally:
        stack.pop()
        if fd is not None:
            try:
                _release(fd)
            finally:
                try:
                    os.close(fd)
                except OSError:  # pragma: no cover - defensive
                    pass
