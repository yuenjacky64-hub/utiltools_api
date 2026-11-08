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

The repo ships no ``pyproject.toml``/``setup.py``, so ``<repo>/src`` is put on
``sys.path`` here exactly as ``tests/conftest.py`` does.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

SRC = Path(__file__).resolve().parent / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from ai_maintainer import checks, status  # noqa: E402  (import after sys.path bootstrap)


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

    parser.print_help(sys.stderr)  # pragma: no cover - unreachable with required=
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
