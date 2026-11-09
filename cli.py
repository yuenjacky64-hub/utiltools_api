"""Repo-root command line entry point for the AI maintainer.

Usage::

    python3 cli.py check
    python3 cli.py status [--json]

``check`` is a stand-alone, read-only pre-flight: it validates the resolved
configuration paths, verifies that ``pi``-hosted models are free tier, reports
orchestrator liveness, and exits 0 when every check passes, 1 when any check
fails. Argparse usage errors keep the stdlib exit code 2 and are never a check
verdict.

``status`` is the read-only reporter next to it: it prints one human-readable
summary, or a single JSON document with ``--json``, and exits 0 once a report is
rendered (the verdict belongs to ``check``).

``stop`` is the operator's wind-down button: it reads ``<state_root>/maintainer.pid``
and either signals the daemon (``SIGTERM`` -> ``request_stop()``) or clears the
stale runtime markers of a dead pid, exiting 0/1 with one explicit message.

The repo ships no ``pyproject.toml``/``setup.py``, so ``<repo>/src`` is put on
``sys.path`` here exactly as ``tests/conftest.py`` does.
"""

from __future__ import annotations

import argparse
import math
import os
import signal
import stat
import sys
import time
from pathlib import Path
from typing import Sequence

SRC = Path(__file__).resolve().parent / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from ai_maintainer import checks, status  # noqa: E402  (import after sys.path bootstrap)
from ai_maintainer.engine import (  # noqa: E402  (import after sys.path bootstrap)
    RUNTIME_HEARTBEAT_FILE,
    RUNTIME_MARKER_TMP_SUFFIX,
    RUNTIME_PID_FILE,
)
from ai_maintainer.shutdown import SHUTDOWN_POLL_INTERVAL_SEC  # noqa: E402
from ai_maintainer.config import MaintainerConfig  # noqa: E402
from ai_maintainer.memory import MEMORY_DOC_NAME, MemoryStore  # noqa: E402


def _repo_root() -> Path:
    """``<repo>`` — the parent of ``cli.py``, matching ``shutdown.default_audit_path()``."""

    return Path(__file__).resolve().parent


def _marker_paths(state_root: Path) -> tuple[Path, ...]:
    """Runtime marker files the wind-down is responsible for removing."""

    return (
        state_root / RUNTIME_PID_FILE,
        state_root / (RUNTIME_PID_FILE + RUNTIME_MARKER_TMP_SUFFIX),
        state_root / RUNTIME_HEARTBEAT_FILE,
        state_root / (RUNTIME_HEARTBEAT_FILE + RUNTIME_MARKER_TMP_SUFFIX),
    )


def _unlink_markers(state_root: Path) -> None:
    """Best-effort removal of every runtime marker; never raises."""

    for marker in _marker_paths(state_root):
        try:
            marker.unlink()
        except OSError:
            pass


def _finite_timeout(raw: str) -> float:
    """argparse type: a finite, non-negative wait, like the engine's own bound.

    ``float("inf")``/``float("nan")`` used to reach the marker poll and turn it
    into an unbounded (``inf``) or instant (``nan``) wait, so the CLI enforces
    the same rule the engine applies to ``max_shutdown_wait_sec``.
    """

    try:
        value = float(raw)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError(f"invalid float value: {raw!r}") from None
    if not math.isfinite(value) or value < 0.0:
        raise argparse.ArgumentTypeError(
            f"timeout must be a finite number of seconds >= 0, got {raw!r}"
        )
    return value


def _run_stop(
    *, maintainer_root: str | None, state_root: str | None, timeout: float
) -> int:
    """Signal the daemon (or clear a dead pid's markers) and report one line."""

    # Defence in depth for callers that bypass ``build_parser()``: an
    # unbounded wait here would hang the operator tool (SHOULD_FIX F4).
    try:
        wait_sec = float(timeout)
    except (TypeError, ValueError):
        print(f"invalid --timeout: {timeout!r}", file=sys.stderr)
        return 1
    if not math.isfinite(wait_sec) or wait_sec < 0.0:
        print(
            f"invalid --timeout {timeout!r}: must be a finite number of "
            "seconds >= 0",
            file=sys.stderr,
        )
        return 1

    root = Path(maintainer_root) if maintainer_root else _repo_root()
    markers_root = Path(state_root) if state_root else root / "data"
    pid_path = markers_root / RUNTIME_PID_FILE

    if not pid_path.exists():
        print(f"maintainer not running (no pid file: {pid_path})")
        return 0

    try:
        raw = pid_path.read_text(encoding="utf-8").strip()
        pid = int(raw)
    except (OSError, ValueError):
        print(f"invalid maintainer pid file: {pid_path}", file=sys.stderr)
        return 1

    if pid <= 1 or pid == os.getpid():
        print(f"invalid maintainer pid file: {pid_path}", file=sys.stderr)
        return 1

    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        _unlink_markers(markers_root)
        print(f"removed stale runtime markers for dead pid {pid}")
        return 0
    except PermissionError:
        print(f"cannot probe maintainer pid {pid}: permission denied", file=sys.stderr)
        return 1
    except OverflowError:
        # A >2**31 pid passes ``int()`` and ``pid <= 1`` but cannot be a real
        # pid; report the malformed file instead of a traceback (SHOULD_FIX F5).
        print(f"invalid maintainer pid file: {pid_path}", file=sys.stderr)
        return 1

    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        _unlink_markers(markers_root)
        print(f"removed stale runtime markers for dead pid {pid}")
        return 0
    except PermissionError:
        print(f"cannot signal maintainer pid {pid}: permission denied", file=sys.stderr)
        return 1
    except OverflowError:
        print(f"invalid maintainer pid file: {pid_path}", file=sys.stderr)
        return 1

    deadline = time.monotonic() + wait_sec
    while time.monotonic() < deadline:
        if not pid_path.exists():
            print(f"maintainer stopped (pid {pid})")
            return 0
        time.sleep(SHUTDOWN_POLL_INTERVAL_SEC)

    if not pid_path.exists():
        print(f"maintainer stopped (pid {pid})")
        return 0

    print(f"stop timed out after {wait_sec}s (pid {pid})", file=sys.stderr)
    return 1


def _memory_path(
    *,
    config_path: str | None,
    maintainer_root: str | None,
    state_root: str | None,
) -> Path | None:
    """``<state_root>/maintainer_memory.json``, or ``None`` when unresolvable."""

    if state_root:
        return Path(state_root).expanduser() / MEMORY_DOC_NAME
    values = checks.resolve_layer_values(
        config_path=config_path, maintainer_root=maintainer_root
    )
    root = checks.resolve_state_root(checks.resolve_path_fields(values))
    if root is None:
        return None
    return root / MEMORY_DOC_NAME


def _memory_counts(memory_path: Path) -> dict[str, int]:
    """Record counts per collection; a missing document counts as zero."""

    document = MemoryStore.load(memory_path).records()
    return {
        name: len(document[name])
        for name in ("runs", "bug_fixes", "learnings")
    }


def _run_memory_export(
    target: str,
    *,
    config_path: str | None,
    maintainer_root: str | None,
    state_root: str | None,
) -> int:
    """Snapshot the memory document to ``target`` and report one count line."""

    out = Path(target).expanduser()
    if out.is_symlink():
        print(f"error: export path is a symlink: {out}", file=sys.stderr)
        return 1
    if out.is_dir():
        print(f"error: export path is a directory: {out}", file=sys.stderr)
        return 1
    parent = out.parent
    if not parent.is_dir():
        print(f"error: export directory does not exist: {parent}", file=sys.stderr)
        return 1
    if not os.access(parent, os.W_OK):
        print(f"error: export directory is not writable: {parent}", file=sys.stderr)
        return 1

    memory_path = _memory_path(
        config_path=config_path,
        maintainer_root=maintainer_root,
        state_root=state_root,
    )
    if memory_path is None:
        print("error: no state root configured", file=sys.stderr)
        return 1

    try:
        document = MemoryStore.load(memory_path).export_memory(out)
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(
        f"exported memory to {out}: runs={len(document['runs'])} "
        f"bug_fixes={len(document['bug_fixes'])} "
        f"learnings={len(document['learnings'])}"
    )
    return 0


def _run_memory_import(
    source: str,
    *,
    config_path: str | None,
    maintainer_root: str | None,
    state_root: str | None,
) -> int:
    """Merge ``source`` into the live memory document and report the outcome."""

    incoming = Path(source).expanduser()
    try:
        info = os.lstat(incoming)
    except OSError:
        print(f"error: import source does not exist: {incoming}", file=sys.stderr)
        return 1
    if not stat.S_ISREG(info.st_mode):
        print(
            f"error: import source is not a regular file: {incoming}",
            file=sys.stderr,
        )
        return 1
    try:
        with open(incoming, "rb"):
            pass
    except OSError:
        print(f"error: import source is not readable: {incoming}", file=sys.stderr)
        return 1

    memory_path = _memory_path(
        config_path=config_path,
        maintainer_root=maintainer_root,
        state_root=state_root,
    )
    if memory_path is None:
        print("error: no state root configured", file=sys.stderr)
        return 1

    try:
        config = MaintainerConfig.load(
            config_path=config_path, maintainer_root=maintainer_root
        )
    except FileNotFoundError:
        print(f"error: config path does not exist: {config_path}", file=sys.stderr)
        return 1
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    parent = memory_path.parent
    # Directories this run would create, deepest first, so a failed import does
    # not leave a fresh state root behind.
    missing: list[Path] = []
    probe = parent
    while not probe.exists() and probe != probe.parent:
        missing.append(probe)
        probe = probe.parent
    try:
        parent.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        print(f"error: cannot create {parent}: {error}", file=sys.stderr)
        return 1

    before = _memory_counts(memory_path)
    issues = MemoryStore.import_memory(
        incoming,
        path=memory_path,
        max_runs=config.memory_max_runs,
        max_bug_fixes=config.memory_max_bug_fixes,
        max_learnings=config.memory_max_learnings,
    )
    after = _memory_counts(memory_path)
    print(
        f"imported memory from {incoming}: "
        f"runs+{max(0, after['runs'] - before['runs'])} "
        f"bug_fixes+{max(0, after['bug_fixes'] - before['bug_fixes'])} "
        f"learnings+{max(0, after['learnings'] - before['learnings'])}"
    )
    for issue in issues:
        print(issue, file=sys.stderr)
    if any(
        issue.startswith(("MEMORY_IMPORT_FAILED:", "MEMORY_CORRUPT:"))
        for issue in issues
    ):
        for directory in missing:
            try:
                directory.rmdir()
            except OSError:
                break
        return 1
    return 0


def _run_memory(args: argparse.Namespace) -> int:
    """Dispatch the ``memory`` subcommand to its export or import half."""

    if args.export_memory:
        return _run_memory_export(
            args.export_memory,
            config_path=args.config,
            maintainer_root=args.maintainer_root,
            state_root=args.state_root,
        )
    return _run_memory_import(
        args.import_memory,
        config_path=args.config,
        maintainer_root=args.maintainer_root,
        state_root=args.state_root,
    )


def build_parser() -> argparse.ArgumentParser:
    """Build the top-level parser with the peer subcommands."""

    parser = argparse.ArgumentParser(
        prog="cli.py",
        description="AI maintainer command line interface.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_parser = subparsers.add_parser(
        "check",
        help="validate configuration paths, model tiers and framework liveness",
        description=(
            "Read-only pre-flight. Validates config paths, free-tier models on "
            "the pi CLI and framework orchestrator liveness. Never modifies "
            "state, enqueues tasks or starts the engine loop."
        ),
    )
    check_parser.add_argument(
        "--config",
        default=None,
        metavar="PATH",
        help="explicit config.json to validate (default: <maintainer_root>/config.json)",
    )
    check_parser.add_argument(
        "--maintainer-root",
        default=None,
        metavar="PATH",
        dest="maintainer_root",
        help="maintainer root to validate (default: MAINTAINER_ROOT, else repo root)",
    )

    status_parser = subparsers.add_parser(
        "status",
        help="report maintainer daemon, orchestrator, queue and counter state",
        description=(
            "Read-only status report. Prints a human-readable summary, or "
            "a single JSON object with --json. Never modifies state, "
            "never starts the engine; exits 0 once a report is rendered."
        ),
    )
    status_parser.add_argument(
        "--json",
        action="store_true",
        dest="as_json",
        help="emit one JSON object on stdout instead of text",
    )
    status_parser.add_argument(
        "--config",
        default=None,
        metavar="PATH",
        help="explicit config.json to read (default: <maintainer_root>/config.json)",
    )
    status_parser.add_argument(
        "--maintainer-root",
        default=None,
        metavar="PATH",
        dest="maintainer_root",
        help="maintainer root to read (default: MAINTAINER_ROOT, else repo root)",
    )

    stop_parser = subparsers.add_parser(
        "stop",
        help="request a clean wind-down, or clear a dead daemon's runtime markers",
        description=(
            "Operator stop. Reads <state_root>/maintainer.pid and signals the "
            "daemon with SIGTERM, waiting up to --timeout for the marker to "
            "disappear. A pid file whose process is gone is removed together "
            "with its heartbeat and .tmp siblings. Exits 0 when clean, 1 on an "
            "invalid pid file, a permission error, or a timeout."
        ),
    )
    stop_parser.add_argument(
        "--maintainer-root",
        default=None,
        metavar="PATH",
        dest="maintainer_root",
        help="maintainer root (default: repo root)",
    )
    stop_parser.add_argument(
        "--state-root",
        default=None,
        metavar="PATH",
        dest="state_root",
        help="directory holding the runtime markers (default: <maintainer_root>/data)",
    )
    stop_parser.add_argument(
        "--timeout",
        type=_finite_timeout,
        default=10.0,
        metavar="SEC",
        dest="timeout",
        help="seconds to wait for the markers to disappear (default: 10.0)",
    )

    memory_parser = subparsers.add_parser(
        "memory",
        help="export or import the maintainer memory document",
        description=(
            "Memory backup and restore. --export-memory PATH writes a "
            "restorable snapshot of <state_root>/maintainer_memory.json; "
            "--import-memory PATH merges a snapshot back into it, skipping "
            "records that already exist and pruning to the configured limits. "
            "Exits 0 when clean and 1 on a path, parse or merge failure."
        ),
    )
    memory_group = memory_parser.add_mutually_exclusive_group(required=True)
    memory_group.add_argument(
        "--export-memory",
        default=None,
        metavar="PATH",
        dest="export_memory",
        help="write a snapshot of the memory document to PATH",
    )
    memory_group.add_argument(
        "--import-memory",
        default=None,
        metavar="PATH",
        dest="import_memory",
        help="merge the memory snapshot at PATH into the live document",
    )
    memory_parser.add_argument(
        "--config",
        default=None,
        metavar="PATH",
        dest="config",
        help="config.json to read (default: MAINTAINER_CONFIG, else the root)",
    )
    memory_parser.add_argument(
        "--maintainer-root",
        default=None,
        metavar="PATH",
        dest="maintainer_root",
        help="maintainer root to read (default: MAINTAINER_ROOT, else repo root)",
    )
    memory_parser.add_argument(
        "--state-root",
        default=None,
        metavar="PATH",
        dest="state_root",
        help="directory holding the memory document (default: resolved data dir)",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Dispatch a parsed command and return its exit code."""

    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "check":
        results, framework_status = checks.run_checks(
            config_path=args.config, maintainer_root=args.maintainer_root
        )
        print(checks.format_report(results, framework_status))
        failures = checks.format_failures(results)
        if failures:
            print(failures, file=sys.stderr)
        return checks.exit_code(results)

    if args.command == "status":
        payload = status.build_status_payload(
            config_path=args.config, maintainer_root=args.maintainer_root
        )
        print(status.render_json(payload) if args.as_json else status.format_text(payload))
        return 0

    if args.command == "stop":
        return _run_stop(
            maintainer_root=args.maintainer_root,
            state_root=args.state_root,
            timeout=args.timeout,
        )

    if args.command == "memory":
        return _run_memory(args)

    parser.print_help(sys.stderr)  # pragma: no cover - unreachable with required=
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
