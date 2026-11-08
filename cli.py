"""Repo-root command line entry point for the AI maintainer.

Usage::

    python3 cli.py check

``check`` is a stand-alone, read-only pre-flight: it validates the resolved
configuration paths, verifies that ``pi``-hosted models are free tier, reports
orchestrator liveness, and exits 0 when every check passes, 1 when any check
fails. Argparse usage errors keep the stdlib exit code 2 and are never a check
verdict.

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

from ai_maintainer import checks  # noqa: E402  (import after sys.path bootstrap)


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
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Dispatch a parsed command and return its exit code."""

    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "check":
        results, status = checks.run_checks(
            config_path=args.config, maintainer_root=args.maintainer_root
        )
        print(checks.format_report(results, status))
        failures = checks.format_failures(results)
        if failures:
            print(failures, file=sys.stderr)
        return checks.exit_code(results)

    parser.print_help(sys.stderr)  # pragma: no cover - unreachable with required=
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
