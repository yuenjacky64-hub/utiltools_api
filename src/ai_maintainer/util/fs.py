"""Process-lifetime instance lock for one maintainer daemon per data directory.

UTILTOOLS-102 contract: **lock ownership - not ``maintainer.pid`` - decides
whether a maintainer daemon is alive.** The pid file is written *after* the
loop starts, so a ``start`` that races into that window sees "no pid" and
builds a second loop (the reported TOCTOU); ``os.kill(pid, 0)`` is satisfied by
any process that happens to hold the number (PID reuse). An OS advisory lock
taken before any marker is written and held for the whole life of
``MaintainerEngine.run_forever`` is the single atomic fact the kernel arbitrates.

Invariants (each is asserted by a DoD row of the plan):

``INV-LOCK-1`` (one resolution site)
    :func:`resolve_lock_path` is the only place a configured
    ``instance_lock_file`` becomes a :class:`~pathlib.Path`. Absolute values are
    kept verbatim, a relative value resolves *under the state root it protects*
    (only its file name is used, so ``..`` cannot escape the data dir) and
    falls back to the repository root when no state root exists.

``INV-LOCK-2`` (never unlinked)
    The lock file is created ``0o600`` and is **never** removed: only truncated
    and rewritten while ``LOCK_EX`` is held. Unlinking on exit would let owner B
    create a fresh inode and lock it while owner A still believes it holds the
    old one - two live daemons, the exact outcome this module exists to prevent.
    A released lock is therefore "free", never "absent".

``INV-LOCK-4`` (probes are read-only)
    :func:`probe_lock` and :func:`read_owner_pid` open ``O_RDONLY|O_NONBLOCK``
    and never create, truncate or unlink anything, so ``status`` / ``stop`` /
    ``run-once`` cannot change liveness for the next ``start``.

``INV-LOCK-7`` (same process, two loops)
    ``flock`` conflicts are detected across open file descriptions, so a second
    ``run_forever`` in the *same* process on the same lock path also raises
    :class:`MaintainerAlreadyRunning`. Two concurrent engines in one process
    must use different state roots.

``INV-LOCK-8`` (total parsers)
    Every helper here is total: an empty file, a missing file, ``"not-a-pid"``,
    a 26-digit number or an unreadable file yields ``None`` /
    ``LOCK_PROBE_UNAVAILABLE`` and never raises.

Operator messages are part of the contract and are spelled once, here:
``maintainer already running (pid N)`` / ``maintainer already running``.
"""

from __future__ import annotations

import errno
import os
import stat
from dataclasses import dataclass
from pathlib import Path

__all__ = [
    "DEFAULT_INSTANCE_LOCK_FILE",
    "LOCK_PROBE_FREE",
    "LOCK_PROBE_HELD",
    "LOCK_PROBE_UNAVAILABLE",
    "InstanceLock",
    "LockProbe",
    "MaintainerAlreadyRunning",
    "pid_is_maintainer",
    "probe_lock",
    "read_owner_pid",
    "resolve_lock_path",
]

try:  # POSIX
    import fcntl
except ImportError:  # pragma: no cover - platform dependent
    fcntl = None  # type: ignore[assignment]

#: Default lock file, relative to the maintainer root (REQ-01).
DEFAULT_INSTANCE_LOCK_FILE = "data/maintainer.lock"

#: Nobody holds the lock (the file may exist; its content is stale debris).
LOCK_PROBE_FREE = "free"
#: Someone holds the lock; ``LockProbe.owner_pid`` names them when readable.
LOCK_PROBE_HELD = "held"
#: The probe itself failed (unreadable file, missing ``flock``); fail closed.
LOCK_PROBE_UNAVAILABLE = "unavailable"

#: Bound on the owner-pid payload we are willing to read back.
_OWNER_PID_READ_LIMIT = 64

_CONTENTION_ERRNOS = frozenset(
    {errno.EACCES, errno.EAGAIN, errno.EWOULDBLOCK}
)


class MaintainerAlreadyRunning(RuntimeError):
    """A live maintainer already owns the instance lock (REQ-03).

    ``owner_pid`` is the pid recorded in the lock file, or ``None`` when the
    file is empty, unreadable or unparsable - so ``str()`` degrades to the bare
    message instead of claiming an owner it could not read (INV-LOCK-8).
    """

    def __init__(self, owner_pid: int | None = None) -> None:
        self.owner_pid = owner_pid
        message = "MaintainerAlreadyRunning: maintainer already running"
        if owner_pid is not None:
            message = f"{message} (pid {owner_pid})"
        super().__init__(message)


@dataclass(frozen=True)
class LockProbe:
    """Result of a non-mutating lock probe. ``state`` is one of ``LOCK_PROBE_*``."""

    state: str
    owner_pid: int | None
    path: Path

    @property
    def held(self) -> bool:
        """``True`` when a live process owns the lock."""

        return self.state == LOCK_PROBE_HELD


def resolve_lock_path(
    raw: str | None, *, state_root: Path | None, repo_root: Path
) -> Path | None:
    """Resolve the configured ``instance_lock_file`` (INV-LOCK-1); ``None`` = off.

    An empty/blank/``None`` value disables the lock (INV-LOCK-3). A relative
    value with a state root resolves to ``<state_root>/<name>``: the lock must
    live in the data directory it protects and a ``..``-laden value cannot move
    it elsewhere. Without a state root the value resolves against
    ``repo_root``.
    """

    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None
    path = Path(text).expanduser()
    if path.is_absolute():
        return path
    if state_root is not None:
        return Path(state_root) / path.name
    return Path(repo_root) / path


def read_owner_pid(path: Path) -> int | None:
    """The pid recorded in ``path``, or ``None``; never raises (INV-LOCK-8)."""

    if path is None:  # pragma: no cover - defensive
        return None
    flags = os.O_RDONLY | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_CLOEXEC", 0)
    try:
        fd = os.open(str(path), flags)
    except OSError:
        return None
    try:
        raw = os.read(fd, _OWNER_PID_READ_LIMIT)
    except OSError:  # pragma: no cover - a regular file read rarely fails
        return None
    finally:
        os.close(fd)
    text = raw.decode("utf-8", "replace").strip()
    if not text:
        return None
    try:
        pid = int(text)
    except ValueError:
        return None
    return pid if pid > 0 else None


def probe_lock(path: Path | None) -> LockProbe:
    """Report who owns ``path`` without mutating anything (INV-LOCK-4).

    ``None`` (lock disabled) and an absent file both report
    :data:`LOCK_PROBE_FREE`: "no file" is not "no liveness", it is exactly the
    pre-first-``start`` state. A file that exists but is not a regular file is
    treated as free - it cannot be a lock this module created.
    """

    if path is None:
        return LockProbe(LOCK_PROBE_FREE, None, Path())
    lock_path = Path(path)
    try:
        info = os.stat(str(lock_path))
    except FileNotFoundError:
        return LockProbe(LOCK_PROBE_FREE, None, lock_path)
    except OSError:
        return LockProbe(LOCK_PROBE_UNAVAILABLE, None, lock_path)
    if not stat.S_ISREG(info.st_mode):
        return LockProbe(LOCK_PROBE_FREE, None, lock_path)
    if fcntl is None:  # pragma: no cover - platform dependent
        return LockProbe(LOCK_PROBE_UNAVAILABLE, None, lock_path)
    flags = os.O_RDONLY | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_CLOEXEC", 0)
    try:
        fd = os.open(str(lock_path), flags)
    except FileNotFoundError:  # pragma: no cover - raced away between stat/open
        return LockProbe(LOCK_PROBE_FREE, None, lock_path)
    except OSError:
        return LockProbe(LOCK_PROBE_UNAVAILABLE, None, lock_path)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            if exc.errno in _CONTENTION_ERRNOS:
                return LockProbe(
                    LOCK_PROBE_HELD, read_owner_pid(lock_path), lock_path
                )
            return LockProbe(LOCK_PROBE_UNAVAILABLE, None, lock_path)
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        except OSError:  # pragma: no cover - releasing a probe is best effort
            pass
        return LockProbe(LOCK_PROBE_FREE, None, lock_path)
    finally:
        os.close(fd)


def pid_is_maintainer(pid: int, *, lock_path: Path | None) -> bool:
    """``True`` only when ``pid`` owns the lock (REQ-04, defeats PID reuse).

    A recycled pid that merely answers ``os.kill(pid, 0)`` is **not** the
    daemon: the lock file must be held *and* must record that pid.
    """

    if isinstance(pid, bool) or not isinstance(pid, int) or pid <= 0:
        return False
    probe = probe_lock(lock_path)
    return probe.state == LOCK_PROBE_HELD and probe.owner_pid == pid


class InstanceLock:
    """A non-blocking, process-lifetime ``flock`` on one stable file.

    :meth:`acquire` is the *only* way a maintainer run becomes live, so a loser
    fails before it touches a pid file, a heartbeat or the stop flag
    (INV-LOCK-5). The lock file is created ``0o600`` with ``O_NOFOLLOW`` and is
    never unlinked (INV-LOCK-2); :meth:`release` unlocks and closes only.
    """

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self._fd: int | None = None
        self._pid: int | None = None

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"InstanceLock(path={str(self.path)!r}, held={self._fd is not None})"

    @property
    def held(self) -> bool:
        """``True`` while this object owns an open, locked descriptor."""

        return self._fd is not None

    def acquire(self) -> None:
        """Take the lock and record the owner pid; raise on contention.

        Raises :class:`MaintainerAlreadyRunning` when another process (or
        another open descriptor in this process, INV-LOCK-7) holds it. Any
        other ``OSError`` propagates: a lock that cannot be taken must never be
        reported as "free" to a second daemon.
        """

        if self._fd is not None:
            return
        if fcntl is None:  # pragma: no cover - platform dependent
            raise RuntimeError(
                f"instance lock unavailable: fcntl is not supported on this platform ({self.path})"
            )
        self.path.parent.mkdir(parents=True, exist_ok=True)
        flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0)
        flags |= getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(str(self.path), flags, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            os.close(fd)
            if exc.errno in _CONTENTION_ERRNOS:
                raise MaintainerAlreadyRunning(read_owner_pid(self.path)) from None
            raise
        try:
            self._write_owner_pid(fd)
        except OSError:  # pragma: no cover - the lock itself is already held
            # The kernel lock is what matters; a read-only or full filesystem
            # only costs ``status`` the owner name (INV-LOCK-8).
            pass
        self._fd = fd
        self._pid = os.getpid()

    def _write_owner_pid(self, fd: int) -> None:
        """Truncate and rewrite the owner pid while ``LOCK_EX`` is held."""

        os.ftruncate(fd, 0)
        os.lseek(fd, 0, os.SEEK_SET)
        os.write(fd, f"{os.getpid()}\n".encode("utf-8"))
        os.fsync(fd)

    def release(self) -> None:
        """Unlock and close; idempotent and never unlinks the file (INV-LOCK-2)."""

        fd = self._fd
        self._fd = None
        self._pid = None
        if fd is None:
            return
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)  # type: ignore[union-attr]
        except OSError:  # pragma: no cover - closing drops the lock anyway
            pass
        finally:
            try:
                os.close(fd)
            except OSError:  # pragma: no cover
                pass

    def owns(self) -> bool:
        """``True`` when this process still holds the lock it names (INV-LOCK-6).

        Both halves matter for the ownership-safe cleanup: the descriptor must
        still be open *and* the lock file must still record this pid, so a
        successor daemon's markers cannot be unlinked by a previous owner.
        """

        if self._fd is None:
            return False
        if self._pid is not None and os.getpid() != self._pid:
            # Forked child: the parent's descriptor was inherited, the lock is
            # not this process' to release or vouch for.
            return False
        return read_owner_pid(self.path) == os.getpid()

    def __enter__(self) -> "InstanceLock":
        self.acquire()
        return self

    def __exit__(self, exc_type: object, exc: object, tb: object) -> None:
        self.release()
