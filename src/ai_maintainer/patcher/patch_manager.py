"""Durable, process-safe autonomous commit budget (UTILTOOLS-126).

``PatchManager`` enforces ``MaintainerConfig.max_commits_per_hour``. The budget
used to be an in-process deque (``self._commit_times``): a daemon restart reset
it and a second maintainer process kept its own copy, so the documented hourly
commit cap could be exceeded. The source of truth is now the ledger file
``<data_dir>/commit_ledger.json``, and every read-modify-write of it runs inside
the existing cross-process ``ai_maintainer.memory.locking.file_lock``.

``INV-COMMIT-1`` (one writer decides the last slot)
    The count and the ``reserved`` append happen in *one* ``file_lock`` section,
    so two managers racing for the final slot serialise: the loser re-reads the
    winner's entry and is refused. ``_commit_budget_available``-style "read, then
    append" code does not exist in this module.

``INV-COMMIT-2`` (durable across restarts and crashes)
    Every entry carries ``ts``/``status``/``pid``/``task_id``/``token``/
    ``commit_sha``. A process that dies between ``reserve`` and ``consume``
    leaves a ``reserved`` entry, which is pruned once it is older than
    :data:`RESERVATION_LEASE_SEC` or once its ``pid`` is gone - so a crash
    wedges the budget for at most one lease, never forever.

``INV-COMMIT-3`` (git is the fallback oracle)
    A commit that landed in git while the ledger write was skipped still
    consumes a slot: every ``reserve`` reconciles the window against commits
    carrying the stable ``Maintainer-Commit: <task_id>`` trailer. Unmarked human
    commits are never counted, and a ``git`` failure degrades to "skip the
    rebuild", never to "skip the cap".

``INV-COMMIT-4`` (corrupt state fails closed)
    An unreadable, blank or malformed ledger is quarantined
    (``commit_ledger.json.quarantined.<epoch-ms>``) and the window is rebuilt
    from trailer-marked git commits only. The cap keeps applying at
    ``max_commits_per_hour``; the *only* way to get an unlimited budget is the
    explicit ``max_commits_per_hour <= 0`` bypass.

The lock target is the ledger path, so ``file_lock`` locks the sibling
``.commit_ledger.json.lock`` (never the ledger inode: writes are
``tmp + os.replace``). ``util.fs.InstanceLock`` is deliberately *not* used here -
it is a process-lifetime lock that is never released and would wedge every
subsequent reservation.
"""

from __future__ import annotations

import json
import logging
import os
import re
import subprocess
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Iterable, Iterator, Mapping

from ai_maintainer.memory.locking import file_lock

__all__ = [
    "CommitBudgetExhausted",
    "CommitLedger",
    "CommitPolicyRefused",
    "CommitVerificationFailed",
    "CommitLedgerCorrupt",
    "LEDGER_CORRUPT",
    "LEDGER_NAME",
    "LEDGER_WINDOW_SEC",
    "MAINTAINER_COMMIT_TRAILER",
    "PatchManager",
    "RESERVATION_LEASE_SEC",
    "STATUS_CANCELLED",
    "STATUS_COMMITTED",
    "STATUS_RESERVED",
    "commit_message_with_trailer",
    "ledger_path",
    "resolve_data_dir",
]

LOG = logging.getLogger(__name__)

#: File name of the ledger inside ``config.data_dir``.
LEDGER_NAME = "commit_ledger.json"

#: Rolling window the cap is measured over (one hour, no calendar arithmetic).
LEDGER_WINDOW_SEC = 3600.0

#: Bounded lease of a ``reserved`` entry (REQ-03). The lease is authoritative:
#: the liveness check below can only prune *earlier*, never extend it.
RESERVATION_LEASE_SEC = 300.0

#: Lock acquisition budget for one ledger section. Expiry is propagated - an
#: unserialised section must never be read as a successful reservation.
LEDGER_LOCK_TIMEOUT_SEC = 10.0

#: Entry states (exact spellings; tests and operators read them).
STATUS_RESERVED = "reserved"
STATUS_COMMITTED = "committed"
STATUS_CANCELLED = "cancelled"
_STATUSES = frozenset({STATUS_RESERVED, STATUS_COMMITTED, STATUS_CANCELLED})

#: Stable commit trailer (REQ-04): ``Maintainer-Commit: <task_id>`` on one line.
MAINTAINER_COMMIT_TRAILER = "Maintainer-Commit"
_TRAILER_PATTERN = re.compile(
    rf"^{re.escape(MAINTAINER_COMMIT_TRAILER)}:\s*(\S+)\s*$", re.MULTILINE
)

#: Log/issue code for a quarantined ledger (one line per incident).
LEDGER_CORRUPT = "COMMIT_LEDGER_CORRUPT"

#: ``<ledger>.quarantined.<epoch-ms>`` - matching the memory store's convention.
QUARANTINE_TAG = "quarantined"

#: Release reasons recorded on a ``cancelled`` entry (REQ-03 release sites).
RELEASE_COMMIT_FAILED = "commit-failed"
RELEASE_ABORTED = "aborted"
RELEASE_TEST_VERIFICATION = "test-verification-failed"
RELEASE_POLICY_REFUSED = "commit-policy-refused"
RELEASE_NON_COMMITTING_REVIEW = "non-committing-review"

#: Default directory under the state root when ``config.data_dir`` is unset.
DEFAULT_DATA_DIR_NAME = "data"

#: One git record: sha, author-time epoch, full message, record separator.
_GIT_LOG_FORMAT = "%H%x1f%ct%x1f%B%x1e"
_GIT_TIMEOUT_SEC = 30.0

#: ``pid`` recorded for entries recovered from git (owner unknown).
RECOVERED_PID = 0


class CommitLedgerCorrupt(Exception):
    """The ledger exists but could not be read as a ledger document."""


class CommitBudgetExhausted(RuntimeError):
    """The hourly autonomous-commit cap refuses another commit."""


class CommitPolicyRefused(RuntimeError):
    """The commit policy refused the message before anything was committed."""


class CommitVerificationFailed(RuntimeError):
    """The post-commit verification (tests) failed, so the slot is released."""


def resolve_data_dir(config: Any) -> Path:
    """Return the directory holding the ledger for ``config``.

    ``config.data_dir`` wins when set; otherwise the ledger sits under
    ``<config.maintainer_root>/data`` (the state root the instance lock and the
    lease audit already use) and, with no root either, under ``./data``.
    """

    raw = getattr(config, "data_dir", None)
    text = None if raw is None else str(raw).strip()
    if text:
        return Path(text).expanduser()
    root = getattr(config, "maintainer_root", None)
    root_text = None if root is None else str(root).strip()
    base = Path(root_text).expanduser() if root_text else Path.cwd()
    return base / DEFAULT_DATA_DIR_NAME


def ledger_path(config: Any) -> Path:
    """The ledger file for ``config`` (``<data_dir>/commit_ledger.json``)."""

    return resolve_data_dir(config) / LEDGER_NAME


def commit_message_with_trailer(task_id: str, summary: str, body: str = "") -> str:
    """Build a commit message ending with the stable ``Maintainer-Commit`` trailer.

    The trailer is added exactly once: a message that already carries the line
    is returned unchanged (modulo surrounding whitespace), so re-rendering a
    message cannot produce a duplicate trailer that reconciliation would have to
    disambiguate.
    """

    trailer = f"{MAINTAINER_COMMIT_TRAILER}: {task_id}"
    parts = [part.strip("\n") for part in (summary, body) if part and part.strip()]
    message = "\n\n".join(parts).strip("\n")
    if _TRAILER_PATTERN.search(message):
        return message
    return f"{message}\n\n{trailer}" if message else trailer


def _trailer_task_id(message: str) -> str | None:
    """The ``Maintainer-Commit`` task id carried by ``message``, if any."""

    match = _TRAILER_PATTERN.search(message or "")
    return match.group(1) if match else None


def _pid_alive(pid: Any) -> bool:
    """``True`` when ``pid`` looks alive.

    ``os.kill(pid, 0)`` is the liveness probe. A recycled pid answers "alive"
    for an unrelated process, which only *under*-prunes (a stale entry keeps
    its slot until the lease expires) - the cap-safe direction. ``pid <= 0``
    means "owner unknown" (git-recovered) and is treated as not alive.
    """

    try:
        pid_int = int(pid)
    except (TypeError, ValueError):
        return False
    if pid_int <= 0:
        return False
    try:
        os.kill(pid_int, 0)
    except ProcessLookupError:
        return False
    except OSError:
        # PermissionError: the process exists but belongs to another user.
        return True
    return True


def run_git_log(repo_root: str | Path, since: float) -> str:
    """``git log`` since ``since`` (epoch seconds), one record per commit.

    Raises ``OSError``/``SubprocessError`` on any git failure; the caller
    degrades to "skip the rebuild".
    """

    completed = subprocess.run(
        [
            "git",
            "-C",
            str(repo_root),
            "log",
            f"--since={int(since)}",
            f"--format={_GIT_LOG_FORMAT}",
        ],
        capture_output=True,
        text=True,
        timeout=_GIT_TIMEOUT_SEC,
        check=False,
    )
    if completed.returncode != 0:
        raise OSError(
            f"git log failed ({completed.returncode}): "
            f"{(completed.stderr or '').strip()[:200]}"
        )
    return completed.stdout


class CommitLedger:
    """The durable commit budget: ``reserved`` / ``committed`` / ``cancelled``.

    One instance is bound to one ledger file. All mutations are serialised by
    :class:`ai_maintainer.memory.locking.file_lock` on that file's sibling lock
    file, so concurrent instances - threads or processes - share one window.
    """

    def __init__(
        self,
        path: str | Path,
        *,
        window_sec: float = LEDGER_WINDOW_SEC,
        lease_sec: float = RESERVATION_LEASE_SEC,
        clock: Callable[[], float] = time.time,
        lock_timeout: float | None = LEDGER_LOCK_TIMEOUT_SEC,
        git_runner: Callable[[str | Path, float], str] | None = None,
    ) -> None:
        self._path = Path(path)
        self._window_sec = float(window_sec)
        self._lease_sec = float(lease_sec)
        self._clock = clock
        self._lock_timeout = lock_timeout
        self._git_runner = git_runner or run_git_log
        self._quarantined: list[Path] = []

    # -- introspection ----------------------------------------------------

    @property
    def path(self) -> Path:
        """The ledger file this instance owns."""

        return self._path

    @property
    def window_sec(self) -> float:
        """The rolling window the cap is measured over."""

        return self._window_sec

    @property
    def lease_sec(self) -> float:
        """The bounded lifetime of a ``reserved`` entry."""

        return self._lease_sec

    @property
    def quarantined_paths(self) -> tuple[Path, ...]:
        """Files this instance quarantined, oldest first (``REQ-05``)."""

        return tuple(self._quarantined)

    def snapshot(self) -> dict[str, Any]:
        """Read-only view of the current window (no reconciliation, no write).

        ``used`` is ``committed + live reserved`` after in-memory pruning, which
        is exactly the number ``reserve`` compares against the cap.
        """

        with self._section():
            now = self._now()
            entries = self._prune(self._load_locked(now, None), now)
            return {
                "path": str(self._path),
                "window_sec": self._window_sec,
                "lease_sec": self._lease_sec,
                "used": self._used(entries),
                "entries": entries,
                "quarantined": [str(item) for item in self._quarantined],
            }

    def used(self) -> int:
        """The number of slots the current window already consumes."""

        return int(self.snapshot()["used"])

    def committed_sha(self, task_id: str) -> list[str]:
        """Commit shas the ledger recorded as committed for ``task_id``."""

        with self._section():
            entries = self._load_locked(self._now(), None)
            return [
                str(entry.get("commit_sha"))
                for entry in entries
                if entry.get("status") == STATUS_COMMITTED
                and entry.get("task_id") == task_id
                and entry.get("commit_sha")
            ]

    # -- REQ-02: atomic check-and-reserve --------------------------------

    def reserve(
        self,
        task_id: str,
        *,
        limit: int,
        repo_root: str | Path | None = None,
    ) -> str | None:
        """Take a slot for ``task_id``; return the token, or ``None`` if refused.

        The load, the reconcile, the prune, the count and the ``reserved``
        append are one ``file_lock`` section (``INV-COMMIT-1``). ``limit <= 0``
        is the documented bypass: a token is always returned, the entry is still
        written so the flow's ``consume``/``release`` stay symmetric, but the
        count never refuses anything.
        """

        with self._section():
            now = self._now()
            entries = self._load_locked(now, repo_root)
            if repo_root is not None:
                # INV-COMMIT-3: a fresh/empty ledger must still absorb the
                # trailer-marked commits already in git (crash before the write).
                self._reconcile_locked(entries, repo_root, now)
            entries = self._prune(entries, now)
            bypass = int(limit) <= 0
            if not bypass and self._used(entries) >= int(limit):
                # Persist the prune/reconcile result: the reconciliation must
                # not re-add the same git commits on the next check.
                self._write_locked(entries)
                return None
            entry = {
                "ts": now,
                "status": STATUS_RESERVED,
                "pid": os.getpid(),
                "task_id": task_id,
                "token": uuid.uuid4().hex,
                "commit_sha": None,
            }
            entries.append(entry)
            self._write_locked(entries)
            return str(entry["token"])

    # -- REQ-03: consume / release ---------------------------------------

    def consume(self, token: str, commit_sha: str | None) -> bool:
        """Turn a ``reserved`` entry into ``committed``; ``False`` if unknown."""

        with self._section():
            now = self._now()
            entries = self._load_locked(now, None)
            for entry in entries:
                if entry.get("token") == token and entry.get("status") == STATUS_RESERVED:
                    entry["status"] = STATUS_COMMITTED
                    entry["commit_sha"] = commit_sha
                    self._write_locked(entries)
                    return True
            return False

    def release(self, token: str, reason: str = "") -> bool:
        """Cancel a ``reserved`` entry so its slot frees immediately (REQ-03)."""

        with self._section():
            now = self._now()
            entries = self._load_locked(now, None)
            for entry in entries:
                if entry.get("token") == token and entry.get("status") == STATUS_RESERVED:
                    entry["status"] = STATUS_CANCELLED
                    entry["commit_sha"] = None
                    if reason:
                        entry["reason"] = reason
                    self._write_locked(entries)
                    return True
            return False

    # -- REQ-04: reconciliation ------------------------------------------

    def reconcile(self, repo_root: str | Path) -> int:
        """Add git commits that carry the trailer but are missing from the ledger.

        Returns the number of entries added (``0`` when git is unavailable or
        nothing is missing). Runs at ``reserve`` time too, so a commit that
        landed just before a crash still consumes a slot on the next check.
        """

        with self._section():
            now = self._now()
            entries = self._load_locked(now, None)
            added = self._reconcile_locked(entries, repo_root, now)
            entries = self._prune(entries, now)
            self._write_locked(entries)
            return added

    # -- internals -------------------------------------------------------

    def _now(self) -> float:
        try:
            return float(self._clock())
        except (TypeError, ValueError):  # pragma: no cover - defensive
            return time.time()

    def _section(self):
        """``file_lock`` over the ledger's sibling lock file.

        The parent directory is created first: ``file_lock`` opens
        ``.<name>.lock`` beside the target and *swallows* an ``OSError`` when it
        cannot (``MEMORY_LOCK_UNAVAILABLE``). For the budget that degradation is
        not acceptable, so a directory that cannot be created raises here
        instead of silently running unserialised.
        """

        self._ensure_dir()
        return _locked(self._path, self._lock_timeout)

    def _ensure_dir(self) -> None:
        parent = self._path.parent
        if parent.is_dir():
            return
        parent.mkdir(parents=True, exist_ok=True)

    def _load_locked(
        self, now: float, repo_root: str | Path | None
    ) -> list[dict[str, Any]]:
        """Read the window; a missing file is empty, a bad one is quarantined."""

        try:
            raw = self._path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return []
        except OSError as error:
            self._quarantine_locked(error)
            return self._rebuild_locked(repo_root, now)
        if not raw.strip():
            self._quarantine_locked("empty document")
            return self._rebuild_locked(repo_root, now)
        try:
            document = json.loads(raw)
        except (json.JSONDecodeError, ValueError) as error:
            self._quarantine_locked(error)
            return self._rebuild_locked(repo_root, now)
        entries = self._validated(document)
        if entries is None:
            self._quarantine_locked("invalid ledger shape")
            return self._rebuild_locked(repo_root, now)
        return entries

    @staticmethod
    def _validated(document: Any) -> list[dict[str, Any]] | None:
        """``entries`` when ``document`` is a well-formed ledger, else ``None``."""

        if not isinstance(document, Mapping):
            return None
        raw_entries = document.get("entries")
        if not isinstance(raw_entries, list):
            return None
        entries: list[dict[str, Any]] = []
        for entry in raw_entries:
            if not isinstance(entry, Mapping):
                return None
            status = entry.get("status")
            if status not in _STATUSES:
                return None
            try:
                ts = float(entry.get("ts"))
            except (TypeError, ValueError):
                return None
            normalised = dict(entry)
            normalised["ts"] = ts
            entries.append(normalised)
        return entries

    def _quarantine_locked(self, reason: Any) -> Path | None:
        """Move the unusable ledger aside; never raises (fail closed instead)."""

        stamp = int(self._now() * 1000)
        candidate = self._path.with_name(f"{self._path.name}.{QUARANTINE_TAG}.{stamp}")
        suffix = 1
        while candidate.exists():
            candidate = self._path.with_name(
                f"{self._path.name}.{QUARANTINE_TAG}.{stamp}-{suffix}"
            )
            suffix += 1
        try:
            os.replace(self._path, candidate)
        except OSError as error:
            LOG.warning(
                "%s: quarantine failed: %s -> %s (%s)",
                LEDGER_CORRUPT,
                self._path,
                candidate,
                type(error).__name__,
            )
            return None
        self._quarantined.append(candidate)
        LOG.warning(
            "%s: commit ledger quarantined: %s -> %s (%s)",
            LEDGER_CORRUPT,
            self._path,
            candidate,
            reason if isinstance(reason, str) else type(reason).__name__,
        )
        return candidate

    def _rebuild_locked(self, repo_root: str | Path | None, now: float) -> list[dict[str, Any]]:
        """Rebuild the window from trailer-marked git commits (REQ-05)."""

        entries: list[dict[str, Any]] = []
        if repo_root is not None:
            self._reconcile_locked(entries, repo_root, now)
        return entries

    def _reconcile_locked(
        self, entries: list[dict[str, Any]], repo_root: str | Path | None, now: float
    ) -> int:
        """Append git-visible trailer commits missing from ``entries``."""

        if repo_root is None:
            return 0
        seen = {str(entry.get("commit_sha")) for entry in entries if entry.get("commit_sha")}
        added = 0
        for sha, ts, task_id in self._git_commits(repo_root, now):
            if sha in seen:
                continue
            seen.add(sha)
            entries.append(
                {
                    "ts": ts,
                    "status": STATUS_COMMITTED,
                    "pid": RECOVERED_PID,
                    "task_id": task_id,
                    "commit_sha": sha,
                }
            )
            added += 1
        return added

    def _git_commits(
        self, repo_root: str | Path, now: float
    ) -> Iterable[tuple[str, float, str]]:
        """Trailer-marked commits inside the window, as ``(sha, ts, task_id)``."""

        since = now - self._window_sec
        try:
            output = self._git_runner(repo_root, since)
        except Exception as error:  # noqa: BLE001 - git is a best-effort oracle
            LOG.warning(
                "commit ledger reconciliation skipped: git unavailable (%s: %s)",
                type(error).__name__,
                error,
            )
            return []
        commits: list[tuple[str, float, str]] = []
        for record in str(output).split("\x1e"):
            fields = record.strip("\n").split("\x1f", 2)
            if len(fields) < 3:
                continue
            sha, ts_text, message = fields[0].strip(), fields[1].strip(), fields[2]
            try:
                ts = float(int(ts_text))
            except (TypeError, ValueError):
                continue
            if ts < since:
                continue
            task_id = _trailer_task_id(message)
            if task_id is None or not sha:
                continue
            commits.append((sha, ts, task_id))
        return commits

    def _prune(self, entries: list[dict[str, Any]], now: float) -> list[dict[str, Any]]:
        """Drop out-of-window entries and expired/crashed reservations."""

        cutoff = now - self._window_sec
        kept: list[dict[str, Any]] = []
        for entry in entries:
            if float(entry.get("ts", 0.0)) < cutoff:
                continue
            if entry.get("status") == STATUS_RESERVED and self._expired(entry, now):
                continue
            kept.append(entry)
        return kept

    def _expired(self, entry: Mapping[str, Any], now: float) -> bool:
        if now - float(entry.get("ts", 0.0)) > self._lease_sec:
            return True
        return not _pid_alive(entry.get("pid"))

    @staticmethod
    def _used(entries: Iterable[Mapping[str, Any]]) -> int:
        """Slots consumed: every ``committed`` entry plus every live reserve."""

        return sum(
            1
            for entry in entries
            if entry.get("status") in (STATUS_COMMITTED, STATUS_RESERVED)
        )

    def _write_locked(self, entries: list[dict[str, Any]]) -> None:
        """Publish ``entries`` atomically (``tmp`` + ``os.replace``)."""

        payload = json.dumps({"entries": entries}, indent=2, sort_keys=True) + "\n"
        tmp = self._path.with_name(f".{self._path.name}.tmp")
        try:
            self._ensure_dir()
            with open(tmp, "w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp, self._path)
        except OSError as error:
            LOG.warning(
                "%s: commit ledger write failed: %s (%s)",
                LEDGER_CORRUPT,
                self._path,
                type(error).__name__,
            )
            try:
                tmp.unlink()
            except OSError:  # pragma: no cover - defensive
                pass


@contextmanager
def _locked(target: Path, timeout: float | None) -> Iterator[None]:
    """``file_lock(target)`` with the ``TimeoutError`` contract kept verbatim."""

    with file_lock(target, timeout=timeout):
        yield


class PatchManager:
    """Autonomous-commit gate for one maintainer root.

    Everything the manager needs is injected: the ``config`` (cap + data dir),
    the ``repo_root`` whose git history is the reconciliation oracle, the
    ``clock`` (so lease/window edges are testable without sleeping) and the
    ``ledger`` itself.
    """

    def __init__(
        self,
        config: Any,
        *,
        repo_root: str | Path | None = None,
        ledger: CommitLedger | None = None,
        clock: Callable[[], float] = time.time,
        git_runner: Callable[[str | Path, float], str] | None = None,
    ) -> None:
        self._config = config
        self._repo_root = None if repo_root is None else Path(repo_root)
        self._ledger = (
            ledger
            if ledger is not None
            else CommitLedger(
                ledger_path(config),
                clock=clock,
                git_runner=git_runner,
            )
        )

    # -- introspection ----------------------------------------------------

    @property
    def config(self) -> Any:
        return self._config

    @property
    def ledger(self) -> CommitLedger:
        return self._ledger

    @property
    def repo_root(self) -> Path | None:
        return self._repo_root

    @property
    def max_commits_per_hour(self) -> int:
        return int(getattr(self._config, "max_commits_per_hour", 0) or 0)

    def used(self) -> int:
        """Slots the current window consumes (committed + live reservations)."""

        return self._ledger.used()

    def snapshot(self) -> dict[str, Any]:
        """The visible budget: used count, window, cap and raw entries."""

        if self._repo_root is not None:
            # Make the visible count match git before reporting it (REQ-04).
            self._ledger.reconcile(self._repo_root)
        view = self._ledger.snapshot()
        view["max_commits_per_hour"] = self.max_commits_per_hour
        view["bypass"] = self.max_commits_per_hour <= 0
        return view

    # -- REQ-02 / REQ-04 --------------------------------------------------

    def reserve(self, task_id: str) -> str | None:
        """Reserve one commit slot, or ``None`` when the cap refuses it."""

        return self._ledger.reserve(
            task_id,
            limit=self.max_commits_per_hour,
            repo_root=self._repo_root,
        )

    def reconcile(self, repo_root: str | Path | None = None) -> int:
        """Count trailer-marked git commits the ledger has not recorded yet."""

        root = self._repo_root if repo_root is None else Path(repo_root)
        if root is None:
            return 0
        return self._ledger.reconcile(root)

    # -- REQ-03 -----------------------------------------------------------

    def consume(self, token: str, commit_sha: str | None) -> bool:
        """Record the commit sha on the reservation (success path)."""

        return self._ledger.consume(token, commit_sha)

    def release(self, token: str, reason: str = "") -> bool:
        """Give the slot back (failure, abort, refusal, non-committing path)."""

        return self._ledger.release(token, reason)

    # -- REQ-04: commit messages -----------------------------------------

    def commit_message(self, task_id: str, summary: str, body: str = "") -> str:
        """A commit message carrying the stable ``Maintainer-Commit`` trailer."""

        return commit_message_with_trailer(task_id, summary, body)

    def commit(
        self,
        *,
        task_id: str,
        summary: str,
        body: str = "",
        commit_fn: Callable[[str], str | None],
        verify_fn: Callable[[str], bool] | None = None,
        policy: Callable[[str], bool] | None = None,
    ) -> str | None:
        """Run the guarded commit and verification block for one slot.

        Every non-committing outcome releases the reservation so the slot is not
        burned (REQ-03):

        * ``policy(message)`` false -> ``CommitPolicyRefused``,
        * ``commit_fn`` raises (commit subprocess failure, abort of any kind) ->
          the exception is re-raised after the release,
        * ``commit_fn`` returns ``None`` (non-committing review path) ->
          ``None`` is returned after the release,
        * ``verify_fn(sha)`` false (test-verification failure) ->
          ``CommitVerificationFailed``.

        Only a sha that survived policy and verification is consumed, with the
        sha recorded on the ledger entry.
        """

        message = self.commit_message(task_id, summary, body)
        token = self.reserve(task_id)
        if token is None:
            raise CommitBudgetExhausted(
                f"hourly commit cap reached: {self.max_commits_per_hour} per "
                f"{int(self._ledger.window_sec)}s"
            )
        if policy is not None and not policy(message):
            self.release(token, RELEASE_POLICY_REFUSED)
            raise CommitPolicyRefused(f"commit policy refused the message: {summary!r}")
        try:
            sha = commit_fn(message)
        except BaseException as error:
            reason = (
                RELEASE_ABORTED
                if isinstance(error, (KeyboardInterrupt, SystemExit))
                else RELEASE_COMMIT_FAILED
            )
            self.release(token, reason)
            raise
        if sha is None:
            self.release(token, RELEASE_NON_COMMITTING_REVIEW)
            return None
        sha = str(sha)
        if verify_fn is not None and not verify_fn(sha):
            self.release(token, RELEASE_TEST_VERIFICATION)
            raise CommitVerificationFailed(f"verification failed for commit {sha}")
        self.consume(token, sha)
        return sha

