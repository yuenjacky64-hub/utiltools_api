"""Tests for bounded cycle-report retention (UTILTOOLS-125).

Every test injects the reports directory explicitly, so an ambient repo-root
``reports/`` directory can never change the outcome.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

import pytest

from ai_maintainer.reporter import MaintenanceReporter

#: Append-only files that share the reports directory and must never be pruned.
LEDGER_NAMES = (
    "maintenance_report.txt",
    "FIX_HISTORY.md",
    "SUGGESTED_FEATURES.md",
    "maintainer.lock",
)


def _write_reports(
    directory: Path, count: int, *, base_mtime: float = 1_000_000.0
) -> list[Path]:
    """Write ``count`` reports, oldest first by mtime; return them in that order."""

    directory.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for index in range(count):
        path = directory / f"RUN-cycle-{index}.md"
        path.write_text(f"# cycle {index}\n", encoding="utf-8")
        stamp = base_mtime + index * 10.0
        os.utime(path, (stamp, stamp))
        paths.append(path)
    return paths


def _write_ledgers(directory: Path) -> dict[Path, str]:
    """Write the ledger files plus an unrelated ``.md``; return their contents."""

    directory.mkdir(parents=True, exist_ok=True)
    contents: dict[Path, str] = {}
    for name in LEDGER_NAMES + ("README.md", "NOTES.md"):
        path = directory / name
        body = f"ledger:{name}\n"
        path.write_text(body, encoding="utf-8")
        contents[path] = body
    return contents


def _assert_ledgers_intact(contents: dict[Path, str]) -> None:
    for path, body in contents.items():
        assert path.is_file(), f"ledger deleted: {path.name}"
        assert path.read_text(encoding="utf-8") == body


# --- REQ-02: ordering and deletion ------------------------------------------


def test_prune_keeps_newest_reports_and_removes_the_rest(tmp_path: Path) -> None:
    """DOD-01 / REQ-02: five reports, limit three, exactly the three newest stay."""

    reports_dir = tmp_path / "reports"
    reports = _write_reports(reports_dir, 5)
    reporter = MaintenanceReporter(reports_dir, max_report_files=3)

    kept, removed = reporter.prune_cycle_reports()

    assert (kept, removed) == (3, 2)
    assert [path.name for path in reporter.list_cycle_reports()] == [
        reports[2].name,
        reports[3].name,
        reports[4].name,
    ]
    assert not reports[0].exists() and not reports[1].exists()


def test_prune_breaks_mtime_ties_by_filename(tmp_path: Path) -> None:
    """REQ-02: equal mtimes still order deterministically (filename tie-break)."""

    reports_dir = tmp_path / "reports"
    reports = _write_reports(reports_dir, 5, base_mtime=1_000_000.0)
    for path in reports:  # collapse every mtime onto one stamp
        os.utime(path, (1_000_000.0, 1_000_000.0))
    reporter = MaintenanceReporter(reports_dir, max_report_files=2)

    kept, removed = reporter.prune_cycle_reports()

    assert (kept, removed) == (2, 3)
    assert sorted(path.name for path in reporter.list_cycle_reports()) == sorted(
        [reports[3].name, reports[4].name]
    )


def test_prune_keep_override_beats_the_configured_limit(tmp_path: Path) -> None:
    """REQ-02: an explicit ``keep`` wins over ``max_report_files``."""

    reports_dir = tmp_path / "reports"
    _write_reports(reports_dir, 4)
    reporter = MaintenanceReporter(reports_dir, max_report_files=500)

    kept, removed = reporter.prune_cycle_reports(keep=1)

    assert (kept, removed) == (1, 3)
    assert len(reporter.list_cycle_reports()) == 1


# --- REQ-02/REQ-03: narrow deletion surface ---------------------------------


def test_prune_preserves_ledgers_and_subdirectories(tmp_path: Path) -> None:
    """DOD-01 / REQ-03: only regular ``RUN-*.md`` files are ever removed."""

    reports_dir = tmp_path / "reports"
    reports = _write_reports(reports_dir, 5)
    ledgers = _write_ledgers(reports_dir)
    # A *directory* that matches the pattern is not a regular file: keep it.
    pattern_dir = reports_dir / "RUN-not-a-file.md"
    pattern_dir.mkdir()
    (pattern_dir / "inner.txt").write_text("keep\n", encoding="utf-8")

    reporter = MaintenanceReporter(reports_dir, max_report_files=3)
    kept, removed = reporter.prune_cycle_reports()

    assert (kept, removed) == (3, 2)
    _assert_ledgers_intact(ledgers)
    assert pattern_dir.is_dir()
    assert (pattern_dir / "inner.txt").is_file()
    assert not reports[0].exists() and not reports[1].exists()


def test_prune_dry_run_changes_nothing(tmp_path: Path) -> None:
    """DOD-02 / REQ-02: a dry run reports the candidates and deletes none."""

    reports_dir = tmp_path / "reports"
    reports = _write_reports(reports_dir, 5)
    ledgers = _write_ledgers(reports_dir)
    reporter = MaintenanceReporter(reports_dir, max_report_files=2)

    kept, removed = reporter.prune_cycle_reports(dry_run=True)

    assert (kept, removed) == (2, 3)
    assert all(path.is_file() for path in reports)
    _assert_ledgers_intact(ledgers)


def test_prune_on_missing_directory_is_a_no_op(tmp_path: Path) -> None:
    """REQ-02: a missing reports directory yields no candidates, never raises."""

    reporter = MaintenanceReporter(tmp_path / "absent", max_report_files=3)

    assert reporter.prune_cycle_reports() == (0, 0)
    assert reporter.list_cycle_reports() == []


def test_prune_rejects_a_nonpositive_limit(tmp_path: Path) -> None:
    """REQ-01 / REQ-02: a limit below one can never invert pruning."""

    reports_dir = tmp_path / "reports"
    _write_reports(reports_dir, 2)

    for value in (0, -1, True):
        with pytest.raises(ValueError, match="positive integer"):
            MaintenanceReporter(reports_dir, max_report_files=value)

    reporter = MaintenanceReporter(reports_dir, max_report_files=2)
    for value in (0, -1):
        with pytest.raises(ValueError, match="positive integer"):
            reporter.prune_cycle_reports(keep=value)
    assert len(reporter.list_cycle_reports()) == 2


# --- REQ-03: pruning is a best-effort step after a successful write ---------


def test_write_cycle_report_prunes_after_writing(tmp_path: Path) -> None:
    """DOD-01 / REQ-03: the write path applies retention to the new report too."""

    reports_dir = tmp_path / "reports"
    _write_reports(reports_dir, 4)
    ledgers = _write_ledgers(reports_dir)
    reporter = MaintenanceReporter(reports_dir, max_report_files=3)

    written = reporter.write_cycle_report("fresh", "# fresh cycle\n")

    assert written == reports_dir / "RUN-fresh.md"
    assert written.read_text(encoding="utf-8") == "# fresh cycle\n"
    remaining = [path.name for path in reporter.list_cycle_reports()]
    assert len(remaining) == 3
    assert "RUN-fresh.md" in remaining
    _assert_ledgers_intact(ledgers)


def test_write_cycle_report_creates_the_directory(tmp_path: Path) -> None:
    """REQ-03: the first write creates the reports directory."""

    reports_dir = tmp_path / "nested" / "reports"
    reporter = MaintenanceReporter(reports_dir, max_report_files=5)

    written = reporter.write_cycle_report("first", "body\n")

    assert written.is_file()
    assert reporter.list_cycle_reports() == [written]


def test_pruning_oserror_is_logged_and_the_new_report_survives(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """DOD-04 / REQ-03: an ``OSError`` while pruning never loses the new report."""

    reports_dir = tmp_path / "reports"
    _write_reports(reports_dir, 4)
    reporter = MaintenanceReporter(reports_dir, max_report_files=2)

    def _boom(self: Path, *args: object, **kwargs: object) -> None:
        raise OSError("unlink denied")

    monkeypatch.setattr(Path, "unlink", _boom)

    with caplog.at_level(logging.ERROR, logger="ai_maintainer.reporter"):
        written = reporter.write_cycle_report("fresh", "# fresh cycle\n")

    assert written.is_file()
    assert written.read_text(encoding="utf-8") == "# fresh cycle\n"
    assert any(
        "report pruning failed" in record.message and "unlink denied" in record.message
        for record in caplog.records
    )
