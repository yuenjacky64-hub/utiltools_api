"""Bounded retention for the per-cycle ``RUN-*.md`` maintenance reports.

A maintainer that runs 24/7 writes one report per cycle, so the reports
directory grows without bound unless the writer also forgets. This module owns
that directory: it writes ``RUN-<cycle_id>.md`` and, after every successful
write, prunes the oldest reports down to ``max_report_files``.

The deletion surface is deliberately narrow: only *regular files* whose name
matches ``^RUN-.*\\.md$`` are candidates, so the append-only ledgers that share
the directory (``maintenance_report.txt``, ``FIX_HISTORY.md``,
``SUGGESTED_FEATURES.md``), the maintainer lock files and any subdirectory are
untouched by construction. Pruning is *best-effort*: a failure is logged and
never turns a written report into a lost cycle.

Design constraints, mirroring the rest of the package: the directory is
injected (this module never guesses a root), and it is the only place that
removes report files.
"""

from __future__ import annotations

import logging
import re
import stat
from pathlib import Path

from .config import DEFAULT_MAX_REPORT_FILES

LOGGER = logging.getLogger("ai_maintainer.reporter")

#: Only these entries may ever be pruned (UTILTOOLS-125 INV-125-A).
CYCLE_REPORT_PATTERN = re.compile(r"^RUN-.*\.md$")

#: One report file per cycle.
CYCLE_REPORT_TEMPLATE = "RUN-{cycle_id}.md"


class MaintenanceReporter:
    """Write per-cycle reports into one directory and keep the newest ``N``.

    ``reports_dir`` is the directory the reports live in; it is created on the
    first write and a missing directory is not an error for pruning (scanning
    yields no candidates).
    """

    def __init__(
        self,
        reports_dir: str | Path,
        *,
        max_report_files: int = DEFAULT_MAX_REPORT_FILES,
    ) -> None:
        limit = max_report_files
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1:
            raise ValueError("max_report_files must be a positive integer")
        self.reports_dir = Path(reports_dir).expanduser()
        self.max_report_files = limit

    # ------------------------------------------------------------------ write
    def write_cycle_report(self, cycle_id: str, body: str) -> Path:
        """Write ``RUN-<cycle_id>.md`` and prune best-effort.

        The report is fully written before pruning starts, so a pruning failure
        is logged and swallowed: the returned path always names the report on
        disk.
        """

        self.reports_dir.mkdir(parents=True, exist_ok=True)
        path = self.reports_dir / CYCLE_REPORT_TEMPLATE.format(cycle_id=cycle_id)
        path.write_text(body, encoding="utf-8")
        try:
            self.prune_cycle_reports()
        except OSError as error:  # best-effort: never lose the new report
            LOGGER.error("report pruning failed: %s", error)
        return path

    # ------------------------------------------------------------------ prune
    def list_cycle_reports(self) -> list[Path]:
        """Cycle reports in the directory, oldest first (filename tie-break)."""

        return [path for _, _, path in self._candidates()]

    def _candidates(self) -> list[tuple[float, str, Path]]:
        """``(mtime, name, path)`` for every prunable report, oldest first.

        ``(mtime, name)`` is the sort key, so equal modification times still
        order deterministically by filename. Entries that are not regular files
        (subdirectories, symlinked directories) and entries that cannot be
        stat'ed are skipped rather than raising.
        """

        try:
            entries = list(self.reports_dir.iterdir())
        except (FileNotFoundError, NotADirectoryError):
            return []
        candidates: list[tuple[float, str, Path]] = []
        for entry in entries:
            if not CYCLE_REPORT_PATTERN.match(entry.name):
                continue
            try:
                info = entry.stat()
            except OSError:  # disappeared between iterdir() and stat()
                continue
            if not stat.S_ISREG(info.st_mode):
                continue
            candidates.append((info.st_mtime, entry.name, entry))
        candidates.sort(key=lambda item: (item[0], item[1]))
        return candidates

    def prune_cycle_reports(
        self, keep: int | None = None, dry_run: bool = False
    ) -> tuple[int, int]:
        """Keep the newest ``keep`` (default: configured) reports.

        Returns ``(kept, removed)``; with ``dry_run=True`` nothing is deleted
        and ``removed`` is the number of files that *would* be removed. The
        configured limit is always ``>= 1`` (validated at construction), so a
        prune can never delete every report.
        """

        limit = self.max_report_files if keep is None else keep
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1:
            raise ValueError("keep must be a positive integer")
        candidates = self._candidates()
        kept = min(limit, len(candidates))
        doomed = candidates[: len(candidates) - kept]
        removed = 0
        for _, _, path in doomed:
            if not dry_run:
                path.unlink()
            removed += 1
        if removed and not dry_run:
            LOGGER.info("pruned %d cycle report(s), kept %d", removed, kept)
        return kept, removed
