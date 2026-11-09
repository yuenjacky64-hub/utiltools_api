"""Tests for memory export/import (UTILTOOLS-79 REQ-01..REQ-04).

Every test builds its own ``tmp_path`` state root and passes explicit
``path``/``maintainer_root``/``env`` values, so an ambient ``config.json`` or a
developer's real ``MAINTAINER_*`` variables can never change the outcome.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

from ai_maintainer.memory import MEMORY_DOC_NAME, MEMORY_DOC_VERSION, MemoryStore
from ai_maintainer.memory.store import atomic_write_text

REPO_ROOT = Path(__file__).resolve().parents[1]
CLI = REPO_ROOT / "cli.py"


def _run(task_id: str, state: str, at: float) -> dict[str, object]:
    return {"run_id": f"run-{task_id}", "task_id": task_id, "state": state, "at": at}


def _bug_fix(commit: str, task_id: str, at: float) -> dict[str, object]:
    return {
        "fix_id": f"fix-{commit}",
        "git_commit": commit,
        "task_id": task_id,
        "summary": f"fixed {task_id}",
        "at": at,
    }


def _learning(learning_id: str, text: str, at: float) -> dict[str, object]:
    return {"learning_id": learning_id, "text": text, "at": at}


def _document(
    *,
    runs: list[dict[str, object]] | None = None,
    bug_fixes: list[dict[str, object]] | None = None,
    learnings: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "version": MEMORY_DOC_VERSION,
        "runs": runs or [],
        "bug_fixes": bug_fixes or [],
        "learnings": learnings or [],
    }


def _write_document(directory: Path, document: object) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / MEMORY_DOC_NAME
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return path


def _seed(directory: Path) -> Path:
    """A small, fully valid document: one run, one fix, one lesson."""

    path = _write_document(
        directory,
        _document(
            runs=[_run("T1", "OK", 10.0)],
            bug_fixes=[_bug_fix("abc1234", "T1", 11.0)],
            learnings=[_learning("L1", "prefer atomic writes", 12.0)],
        ),
    )
    return path


def _full_document() -> dict[str, object]:
    return _document(
        runs=[_run("T2", "OK", 20.0), _run("T3", "FAILED", 30.0)],
        bug_fixes=[_bug_fix("def5678", "T2", 21.0)],
        learnings=[_learning("L2", "prune before save", 22.0)],
    )


def _cli(args: list[str], **env_overrides: object) -> subprocess.CompletedProcess[str]:
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    env.update({key: str(value) for key, value in env_overrides.items()})
    return subprocess.run(
        [sys.executable, str(CLI), *args],
        cwd=str(REPO_ROOT),
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )


# --- REQ-01: export_memory -------------------------------------------------


def test_export_returns_all_three_collections(tmp_path: Path) -> None:
    path = _seed(tmp_path / "state")
    document = MemoryStore.load(path).export_memory()
    assert set(document) == {"version", "runs", "bug_fixes", "learnings"}
    assert document["version"] == MEMORY_DOC_VERSION
    assert [run["task_id"] for run in document["runs"]] == ["T1"]
    assert [fix["git_commit"] for fix in document["bug_fixes"]] == ["abc1234"]
    assert [lesson["learning_id"] for lesson in document["learnings"]] == ["L1"]
    assert document["bug_fixes"][0]["summary"] == "fixed T1"


def test_export_writes_a_file_that_loads_back(tmp_path: Path) -> None:
    root = tmp_path
    path = _seed(root / "state")
    target = root / "backup" / "memory.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    document = MemoryStore.load(path).export_memory(target)
    assert target.is_file()
    assert json.loads(target.read_text(encoding="utf-8")) == document
    assert MemoryStore.load(target).records() == document


def test_export_does_not_mutate_the_live_store(tmp_path: Path) -> None:
    root = tmp_path
    path = _write_document(
        root,
        _document(runs=[_run("T3", "OK", 30.0), _run("T1", "OK", 10.0)]),
    )
    before_bytes = path.read_bytes()
    store = MemoryStore.load(path)
    before_records = store.records()
    export = store.export_memory()
    export["runs"].append(_run("T9", "OK", 90.0))
    export["runs"][0]["state"] = "MUTATED"
    assert [run["task_id"] for run in store.records()["runs"]] == ["T3", "T1"]
    assert store.records() == before_records
    assert path.read_bytes() == before_bytes


def test_export_orders_runs_newest_last(tmp_path: Path) -> None:
    root = tmp_path
    path = _write_document(
        root,
        _document(runs=[_run("T3", "OK", 30.0), _run("T1", "OK", 10.0), _run("T2", "OK", 20.0)]),
    )
    document = MemoryStore.load(path).export_memory()
    assert [run["task_id"] for run in document["runs"]] == ["T1", "T2", "T3"]
    assert [run["at"] for run in document["runs"]] == [10.0, 20.0, 30.0]


def test_export_rejects_the_live_document_path(tmp_path: Path) -> None:
    root = tmp_path
    path = _seed(root)
    import pytest

    with pytest.raises(ValueError):
        MemoryStore.load(path).export_memory(path)
    assert json.loads(path.read_text(encoding="utf-8"))["runs"]


def test_export_refuses_a_corrupt_document(tmp_path: Path) -> None:
    root = tmp_path
    path = root / MEMORY_DOC_NAME
    root.mkdir(parents=True, exist_ok=True)
    path.write_text("{not json", encoding="utf-8")
    import pytest

    with pytest.raises(ValueError):
        MemoryStore.load(path).export_memory()


def test_export_missing_document_is_an_empty_envelope(tmp_path: Path) -> None:
    root = tmp_path
    document = MemoryStore.load(root / MEMORY_DOC_NAME).export_memory()
    assert document == _document()


# --- REQ-02: import_memory -------------------------------------------------


def test_import_from_dict_merges_into_empty_store(tmp_path: Path) -> None:
    root = tmp_path
    path = root / MEMORY_DOC_NAME
    issues = MemoryStore.import_memory(_full_document(), path=path)
    assert issues == []
    records = MemoryStore.load(path).records()
    assert [run["task_id"] for run in records["runs"]] == ["T2", "T3"]
    assert [fix["git_commit"] for fix in records["bug_fixes"]] == ["def5678"]
    assert [lesson["learning_id"] for lesson in records["learnings"]] == ["L2"]


def test_import_from_file_path_round_trips_an_export(tmp_path: Path) -> None:
    root = tmp_path
    source_doc = _full_document()
    source = root / "snapshot.json"
    atomic_write_text(source, json.dumps(source_doc) + "\n")
    target = root / "restored" / MEMORY_DOC_NAME
    target.parent.mkdir(parents=True, exist_ok=True)
    assert MemoryStore.import_memory(str(source), path=target) == []
    assert MemoryStore.load(target).records() == source_doc


def test_import_does_not_duplicate_runs_by_task_id(tmp_path: Path) -> None:
    root = tmp_path
    path = _seed(root)
    before = path.read_bytes()
    issues = MemoryStore.import_memory(
        _document(runs=[_run("T1", "OK", 10.0)]), path=path
    )
    assert any(issue.startswith("MEMORY_IMPORT_SKIPPED: duplicates runs") for issue in issues)
    assert path.read_bytes() == before


def test_import_does_not_duplicate_bug_fixes_by_git_commit(tmp_path: Path) -> None:
    root = tmp_path
    path = _seed(root)
    before = path.read_bytes()
    issues = MemoryStore.import_memory(
        _document(bug_fixes=[_bug_fix("abc1234", "T1", 11.0)]), path=path
    )
    assert any(
        issue.startswith("MEMORY_IMPORT_SKIPPED: duplicates bug_fixes")
        for issue in issues
    )
    assert path.read_bytes() == before


def test_import_does_not_duplicate_learnings_by_at(tmp_path: Path) -> None:
    root = tmp_path
    path = _seed(root)
    before = path.read_bytes()
    issues = MemoryStore.import_memory(
        _document(learnings=[_learning("L-other", "different text", 12.0)]), path=path
    )
    assert any(
        issue.startswith("MEMORY_IMPORT_SKIPPED: duplicates learnings")
        for issue in issues
    )
    assert path.read_bytes() == before


def test_repeated_import_is_idempotent(tmp_path: Path) -> None:
    root = tmp_path
    path = root / MEMORY_DOC_NAME
    snapshot = _full_document()
    assert MemoryStore.import_memory(snapshot, path=path) == []
    first = path.read_bytes()
    second_issues = MemoryStore.import_memory(snapshot, path=path)
    assert path.read_bytes() == first
    assert len(second_issues) == 4
    assert all(
        issue.startswith("MEMORY_IMPORT_SKIPPED: duplicates")
        for issue in second_issues
    )


def test_import_prunes_to_the_given_limits(tmp_path: Path) -> None:
    root = tmp_path
    path = root / MEMORY_DOC_NAME
    runs = [_run(f"T{index}", "OK", float(index)) for index in range(1, 5)]
    issues = MemoryStore.import_memory(
        _document(runs=runs), path=path, max_runs=2
    )
    assert any(issue.startswith("MEMORY_IMPORT_PRUNED: runs dropped") for issue in issues)
    kept = MemoryStore.load(path).records()["runs"]
    assert [run["task_id"] for run in kept] == ["T3", "T4"]


def test_import_reports_missing_and_invalid_sources(tmp_path: Path) -> None:
    root = tmp_path
    path = root / MEMORY_DOC_NAME
    missing = MemoryStore.import_memory(root / "nope.json", path=path)
    assert missing == ["MEMORY_IMPORT_FAILED: not a regular file"]
    (root / "broken.json").write_text("{oops", encoding="utf-8")
    assert MemoryStore.import_memory(root / "broken.json", path=path) == [
        "MEMORY_IMPORT_FAILED: not valid JSON"
    ]
    unsupported = MemoryStore.import_memory(
        {"version": 2, "runs": [], "bug_fixes": [], "learnings": []}, path=path
    )
    assert len(unsupported) == 1
    assert unsupported[0].startswith(
        "MEMORY_IMPORT_FAILED: unsupported document version"
    )
    assert not path.exists()


def test_import_into_a_corrupt_store_writes_nothing(tmp_path: Path) -> None:
    root = tmp_path
    root.mkdir(parents=True, exist_ok=True)
    path = root / MEMORY_DOC_NAME
    path.write_text("{not json", encoding="utf-8")
    issues = MemoryStore.import_memory(_full_document(), path=path)
    assert len(issues) == 1 and issues[0].startswith("MEMORY_CORRUPT:")
    assert path.read_text(encoding="utf-8") == "{not json"


def test_import_skips_a_record_that_would_break_the_collection(tmp_path: Path) -> None:
    root = tmp_path
    path = _seed(root)
    issues = MemoryStore.import_memory(
        _document(runs=[{"run_id": "run-T7", "task_id": "T7", "at": 40.0}]), path=path
    )
    assert any(
        issue.startswith("MEMORY_IMPORT_SKIPPED: would break runs") for issue in issues
    )
    assert [run["task_id"] for run in MemoryStore.load(path).records()["runs"]] == ["T1"]


# --- REQ-03: CLI -----------------------------------------------------------


def _config(root: Path) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    path = root / "config.json"
    path.write_text(
        json.dumps(
            {
                "memory_max_runs": 50,
                "memory_max_bug_fixes": 50,
                "memory_max_learnings": 50,
            }
        ),
        encoding="utf-8",
    )
    return path


def test_cli_export_then_import_round_trip(tmp_path: Path) -> None:
    root = tmp_path
    state = root / "state"
    _seed(state)
    config = _config(root)
    target = root / "backup.json"
    exported = _cli(
        [
            "memory",
            "--export-memory",
            str(target),
            "--state-root",
            str(state),
            "--config",
            str(config),
            "--maintainer-root",
            str(root),
        ]
    )
    assert exported.returncode == 0, exported.stderr
    assert "runs=1" in exported.stdout and "bug_fixes=1" in exported.stdout
    assert "learnings=1" in exported.stdout

    restored = root / "restored"
    imported = _cli(
        [
            "memory",
            "--import-memory",
            str(target),
            "--state-root",
            str(restored),
            "--config",
            str(config),
            "--maintainer-root",
            str(root),
        ]
    )
    assert imported.returncode == 0, imported.stderr
    assert "runs+1 bug_fixes+1 learnings+1" in imported.stdout
    assert MemoryStore.load(restored / MEMORY_DOC_NAME).records() == json.loads(
        target.read_text(encoding="utf-8")
    )


def test_cli_import_of_an_already_imported_file_reports_skips(tmp_path: Path) -> None:
    root = tmp_path
    state = root / "state"
    _seed(state)
    config = _config(root)
    target = root / "backup.json"
    assert (
        _cli(["memory", "--export-memory", str(target), "--state-root", str(state)]).returncode
        == 0
    )
    args = [
        "memory",
        "--import-memory",
        str(target),
        "--state-root",
        str(state),
        "--config",
        str(config),
        "--maintainer-root",
        str(root),
    ]
    assert _cli(args).returncode == 0
    repeat = _cli(args)
    assert repeat.returncode == 0, repeat.stderr
    assert "runs+0 bug_fixes+0 learnings+0" in repeat.stdout
    assert "MEMORY_IMPORT_SKIPPED: duplicates runs" in repeat.stderr


def test_cli_validates_paths(tmp_path: Path) -> None:
    root = tmp_path
    state = root / "state"
    _seed(state)
    missing_dir = _cli(
        ["memory", "--export-memory", str(root / "no-such-dir" / "m.json"), "--state-root", str(state)]
    )
    assert missing_dir.returncode == 1
    assert "export directory does not exist" in missing_dir.stderr

    as_dir = _cli(["memory", "--export-memory", str(root), "--state-root", str(state)])
    assert as_dir.returncode == 1
    assert "export path is a directory" in as_dir.stderr

    missing_source = _cli(
        ["memory", "--import-memory", str(root / "gone.json"), "--state-root", str(state)]
    )
    assert missing_source.returncode == 1
    assert "import source does not exist" in missing_source.stderr

    not_a_file = _cli(
        ["memory", "--import-memory", str(root), "--state-root", str(state)]
    )
    assert not_a_file.returncode == 1
    assert "not a regular file" in not_a_file.stderr

    no_state = _cli(
        ["memory", "--export-memory", str(root / "out.json"), "--maintainer-root", str(root)]
    )
    assert no_state.returncode == 1
    assert "no state root configured" in no_state.stderr


def test_cli_import_failure_on_unparsable_snapshot(tmp_path: Path) -> None:
    root = tmp_path
    state = root / "state"
    _seed(state)
    config = _config(root)
    bad = root / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    result = _cli(
        [
            "memory",
            "--import-memory",
            str(bad),
            "--state-root",
            str(state),
            "--config",
            str(config),
            "--maintainer-root",
            str(root),
        ]
    )
    assert result.returncode == 1
    assert "MEMORY_IMPORT_FAILED: not valid JSON" in result.stderr


def test_cli_memory_requires_exactly_one_direction(tmp_path: Path) -> None:
    none_given = _cli(["memory"])
    assert none_given.returncode == 2
    both = _cli(["memory", "--export-memory", "a.json", "--import-memory", "b.json"])
    assert both.returncode == 2


def test_cli_help_lists_the_memory_subcommand(tmp_path: Path) -> None:
    result = _cli(["--help"])
    assert result.returncode == 0
    assert "memory" in result.stdout
    memory_help = _cli(["memory", "--help"])
    assert memory_help.returncode == 0
    assert "--export-memory" in memory_help.stdout
    assert "--import-memory" in memory_help.stdout
