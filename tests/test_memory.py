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

import pytest

from ai_maintainer.consistency import validate_memory_records
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


def test_export_memory_returns_all_collections(tmp_path: Path) -> None:
    path = _seed(tmp_path / "state")
    siblings = sorted(child.name for child in path.parent.iterdir())
    document = MemoryStore.load(path).export_memory()
    # ``path=None`` writes nothing: no new file, no leftover ``*.tmp`` sibling.
    assert sorted(child.name for child in path.parent.iterdir()) == siblings
    assert set(document) == {"version", "runs", "bug_fixes", "learnings"}
    assert document["version"] == MEMORY_DOC_VERSION
    assert [run["task_id"] for run in document["runs"]] == ["T1"]
    assert [fix["git_commit"] for fix in document["bug_fixes"]] == ["abc1234"]
    assert [lesson["learning_id"] for lesson in document["learnings"]] == ["L1"]
    assert document["bug_fixes"][0]["summary"] == "fixed T1"


def test_export_memory_writes_atomically(tmp_path: Path) -> None:
    """DOD-02: exact ``atomic_write_text`` bytes, no ``.tmp`` sibling, symlink refused."""

    root = tmp_path
    path = _seed(root / "state")
    target = root / "backup" / "memory.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    document = MemoryStore.load(path).export_memory(target)

    # Exactly the bytes ``MemoryStore.save`` persists: indent=2, sort_keys=True.
    assert target.read_text(encoding="utf-8") == (
        json.dumps(document, indent=2, sort_keys=True) + "\n"
    )
    assert json.loads(target.read_text(encoding="utf-8")) == document
    assert validate_memory_records(json.loads(target.read_text(encoding="utf-8"))) == []
    assert MemoryStore.load(target).records() == document
    # The temporary sibling never survives a successful write.
    assert sorted(child.name for child in target.parent.iterdir()) == [target.name]

    # A pre-existing symlink at the target is refused and the link target keeps
    # its bytes; no ``*.tmp`` sibling is left behind either.
    outside = root / "outside.json"
    outside.write_text("{}\n", encoding="utf-8")
    link = root / "link.json"
    link.symlink_to(outside)
    with pytest.raises(OSError):
        MemoryStore.load(path).export_memory(link)
    assert outside.read_text(encoding="utf-8") == "{}\n"
    assert not link.with_name(link.name + ".tmp").exists()


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


def test_export_memory_orders_runs_by_timestamp(tmp_path: Path) -> None:
    """DOD-03: ascending, newest last, missing/``None`` ``at`` first, stable."""

    root = tmp_path
    unordered = [
        _run("T3", "OK", 30.0),
        _run("T1", "OK", 10.0),
        {"run_id": "run-T9", "task_id": "T9", "state": "OK"},
        _run("T2", "OK", 20.0),
        {"run_id": "run-T8", "task_id": "T8", "state": "OK", "at": None},
    ]
    path = _write_document(root, _document(runs=unordered))
    store = MemoryStore.load(path)
    before = store.records()

    document = store.export_memory()
    assert [run["task_id"] for run in document["runs"]] == [
        "T9",
        "T8",
        "T1",
        "T2",
        "T3",
    ]
    assert [run["at"] for run in document["runs"][2:]] == [10.0, 20.0, 30.0]

    # A second call returns the same order: the live store was never re-sorted.
    expected = ["T9", "T8", "T1", "T2", "T3"]
    assert [run["task_id"] for run in store.export_memory()["runs"]] == expected
    assert store.records() == before
    assert json.loads(path.read_text(encoding="utf-8"))["runs"] == unordered


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


def test_import_memory_accepts_dict_and_path(tmp_path: Path) -> None:
    """DOD-04: both source kinds return ``list[str]`` and keep fields intact."""

    root = tmp_path
    from_dict = root / "dict" / MEMORY_DOC_NAME
    from_dict.parent.mkdir(parents=True, exist_ok=True)
    issues = MemoryStore.import_memory(_full_document(), path=from_dict)
    assert isinstance(issues, list) and issues == []
    records = MemoryStore.load(from_dict).records()
    assert [run["task_id"] for run in records["runs"]] == ["T2", "T3"]
    assert [fix["git_commit"] for fix in records["bug_fixes"]] == ["def5678"]
    assert [lesson["learning_id"] for lesson in records["learnings"]] == ["L2"]

    source_doc = _full_document()
    source = root / "snapshot.json"
    atomic_write_text(source, json.dumps(source_doc) + "\n")
    for source_arg in (str(source), source):
        target = root / f"restored-{type(source_arg).__name__}" / MEMORY_DOC_NAME
        target.parent.mkdir(parents=True, exist_ok=True)
        outcome = MemoryStore.import_memory(source_arg, path=target)
        assert isinstance(outcome, list) and outcome == []
        # Field structure is preserved verbatim: records are copied, not projected.
        assert MemoryStore.load(target).records() == source_doc


def test_import_memory_dedupes_by_task_id_git_commit_and_timestamp(
    tmp_path: Path,
) -> None:
    """DOD-05: one skip per brief key, collection lengths unchanged."""

    root = tmp_path
    path = _seed(root)
    before = path.read_bytes()
    issues = MemoryStore.import_memory(
        _document(
            runs=[_run("T1", "OK", 10.0)],
            bug_fixes=[_bug_fix("abc1234", "T1", 11.0)],
            learnings=[_learning("L-other", "different text", 12.0)],
        ),
        path=path,
    )
    assert [issue.split(" (")[0] for issue in issues] == [
        "MEMORY_IMPORT_SKIPPED: duplicates runs",
        "MEMORY_IMPORT_SKIPPED: duplicates bug_fixes",
        "MEMORY_IMPORT_SKIPPED: duplicates learnings",
    ]
    records = MemoryStore.load(path).records()
    assert [len(records[name]) for name in ("runs", "bug_fixes", "learnings")] == [
        1,
        1,
        1,
    ]
    assert path.read_bytes() == before


def test_import_memory_is_idempotent(tmp_path: Path) -> None:
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


def test_import_memory_prunes_to_limits(tmp_path: Path) -> None:
    """DOD-09: over-limit drops the oldest with a string; exact limit is silent."""

    root = tmp_path
    path = root / MEMORY_DOC_NAME
    runs = [_run(f"T{index}", "OK", float(index)) for index in range(1, 5)]
    issues = MemoryStore.import_memory(_document(runs=runs), path=path, max_runs=2)
    assert any(
        issue.startswith("MEMORY_IMPORT_PRUNED: runs dropped") for issue in issues
    )
    kept = MemoryStore.load(path).records()["runs"]
    assert [run["task_id"] for run in kept] == ["T3", "T4"]

    # Boundary: limits reached exactly produce no prune string at all.
    exact = root / "exact" / MEMORY_DOC_NAME
    exact.parent.mkdir(parents=True, exist_ok=True)
    exact_issues = MemoryStore.import_memory(
        _document(
            runs=[_run("T1", "OK", 1.0), _run("T2", "OK", 2.0)],
            bug_fixes=[_bug_fix("abc1234", "T1", 3.0)],
            learnings=[_learning("L1", "exactly at the limit", 4.0)],
        ),
        path=exact,
        max_runs=2,
        max_bug_fixes=1,
        max_learnings=1,
    )
    assert exact_issues == []
    kept_exact = MemoryStore.load(exact).records()
    assert [len(kept_exact[name]) for name in ("runs", "bug_fixes", "learnings")] == [
        2,
        1,
        1,
    ]


def test_import_memory_reports_failures_without_raising(tmp_path: Path) -> None:
    """DOD-08: missing / non-regular / oversized / non-JSON / wrong version."""

    root = tmp_path
    path = root / MEMORY_DOC_NAME

    missing = MemoryStore.import_memory(root / "nope.json", path=path)
    assert missing == ["MEMORY_IMPORT_FAILED: not a regular file"]

    (root / "a-directory.json").mkdir()
    assert MemoryStore.import_memory(root / "a-directory.json", path=path) == [
        "MEMORY_IMPORT_FAILED: not a regular file"
    ]

    # ``st_size`` guard: > MEMORY_READ_LIMIT (65536) bytes, never read.
    oversized = root / "oversized.json"
    oversized.write_text("{}" + " " * 70_000, encoding="utf-8")
    assert oversized.stat().st_size > 65_536
    assert MemoryStore.import_memory(oversized, path=path) == [
        "MEMORY_IMPORT_FAILED: larger than 65536 bytes"
    ]

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

    # Not a mapping and not a path: still a string, never a TypeError.
    assert MemoryStore.import_memory(123, path=path) == [
        "MEMORY_IMPORT_FAILED: source is not a mapping or a path"
    ]
    assert not path.exists()


def test_import_memory_aborts_on_corrupt_target(tmp_path: Path) -> None:
    root = tmp_path
    root.mkdir(parents=True, exist_ok=True)
    path = root / MEMORY_DOC_NAME
    path.write_text("{not json", encoding="utf-8")
    issues = MemoryStore.import_memory(_full_document(), path=path)
    assert len(issues) == 1 and issues[0].startswith("MEMORY_CORRUPT:")
    assert path.read_text(encoding="utf-8") == "{not json"


def test_import_memory_never_persists_an_invalid_document(tmp_path: Path) -> None:
    """DOD-06: ``run_id`` / ``fix_id`` / normalised-``text`` collisions roll back."""

    root = tmp_path
    path = _seed(root)
    before = path.read_bytes()
    issues = MemoryStore.import_memory(
        _document(
            runs=[{"run_id": "run-T1", "task_id": "T7", "state": "OK", "at": 40.0}],
            bug_fixes=[
                {
                    "fix_id": "fix-abc1234",
                    "git_commit": "fff9999",
                    "task_id": "T1",
                    "summary": "collides on fix_id",
                    "at": 41.0,
                }
            ],
            learnings=[
                {
                    "learning_id": "L9",
                    "text": "  Prefer   ATOMIC writes ",
                    "at": 42.0,
                }
            ],
        ),
        path=path,
    )
    assert [issue.split(" (")[0] for issue in issues] == [
        "MEMORY_IMPORT_SKIPPED: would break runs",
        "MEMORY_IMPORT_SKIPPED: would break bug_fixes",
        "MEMORY_IMPORT_SKIPPED: would break learnings",
    ]
    records = MemoryStore.load(path).records()
    assert [len(records[name]) for name in ("runs", "bug_fixes", "learnings")] == [
        1,
        1,
        1,
    ]
    assert records["learnings"][0]["learning_id"] == "L1"
    # The persisted document still satisfies the validators with zero errors.
    assert validate_memory_records(records) == []
    assert path.read_bytes() == before


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


def test_cli_memory_export_roundtrip(tmp_path: Path) -> None:
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
    # DOD-11: exactly one summary line, in the documented shape.
    assert exported.stdout.strip() == (
        f"exported memory to {target}: runs=1 bug_fixes=1 learnings=1"
    )

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


def test_cli_memory_import_and_path_validation(tmp_path: Path) -> None:
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

    # Parent exists but is a regular file: the export directory is unusable.
    blocker = root / "blocker.txt"
    blocker.write_text("x", encoding="utf-8")
    blocked_parent = _cli(
        ["memory", "--export-memory", str(blocker / "m.json"), "--state-root", str(state)]
    )
    assert blocked_parent.returncode == 1
    assert "export directory does not exist" in blocked_parent.stderr

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

    source = root / "import-source.json"
    source.write_text(json.dumps(_document()) + "\n", encoding="utf-8")
    no_state_import = _cli(
        ["memory", "--import-memory", str(source), "--maintainer-root", str(root)]
    )
    assert no_state_import.returncode == 1
    assert "no state root configured" in no_state_import.stderr


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

    # A failed import must not leave a fresh state root behind (cli.py).
    fresh = root / "fresh" / "nested" / "state"
    fresh_result = _cli(
        [
            "memory",
            "--import-memory",
            str(bad),
            "--state-root",
            str(fresh),
            "--config",
            str(config),
            "--maintainer-root",
            str(root),
        ]
    )
    assert fresh_result.returncode == 1
    assert not fresh.exists()


def test_cli_memory_requires_exactly_one_flag(tmp_path: Path) -> None:
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


# --- REQ-01/03/04/06: MemoryStore.validate() -------------------------------


def _labelled_bug_fix(
    commit: str, task_id: str, at: float, *, component: str | None = None
) -> dict[str, object]:
    """Bug fix carrying ``component`` only when asked (ASM-01)."""

    record = _bug_fix(commit, task_id, at)
    if component is not None:
        record.pop("summary", None)
        record["component"] = component
    return record


def test_validate_accepts_a_valid_document(tmp_path: Path) -> None:
    """DOD-01: a clean document returns ``[]`` and writes nothing."""

    path = _seed(tmp_path / "state")
    store = MemoryStore.load(path)
    assert store.validate() == []
    assert store.validate(max_runs=50, max_bug_fixes=50, max_learnings=50) == []


def test_validate_flags_unsorted_runs(tmp_path: Path) -> None:
    """DOD-04: newest-first runs are reported once per inversion with indices."""

    path = _write_document(
        tmp_path / "state",
        _document(runs=[_run("T2", "OK", 30.0), _run("T1", "OK", 10.0)]),
    )
    errors = MemoryStore.load(path).validate()
    assert len(errors) == 1
    assert errors[0].startswith("MEMORY_ORDER: runs[1].at ")
    assert "runs[0].at" in errors[0]


def test_validate_flags_missing_run_timestamp(tmp_path: Path) -> None:
    """DOD-09: a run without ``at`` is reported as missing."""

    broken = _run("T2", "OK", 0.0)
    broken.pop("at")
    path = _write_document(
        tmp_path / "state",
        _document(runs=[_run("T1", "OK", 10.0), broken]),
    )
    errors = MemoryStore.load(path).validate()
    assert "MEMORY_ORDER: runs[1].at is missing" in errors


def test_validate_flags_missing_bug_fix_fields(tmp_path: Path) -> None:
    """DOD-02: blank ``task_id``/``git_commit`` are reported per record."""

    broken = _bug_fix("abc1234", "T1", 11.0)
    broken["task_id"] = "   "
    broken["git_commit"] = ""
    path = _write_document(tmp_path / "state", _document(bug_fixes=[broken]))
    errors = MemoryStore.load(path).validate()
    assert "BUG_FIX_MISSING_FIELD: bug_fixes[0].task_id is missing" in errors
    assert "BUG_FIX_MISSING_FIELD: bug_fixes[0].git_commit is missing" in errors


def test_validate_accepts_component_and_summary_labels(tmp_path: Path) -> None:
    """DOD-05: ``component`` or ``summary`` satisfies the label rule."""

    path = _write_document(
        tmp_path / "state",
        _document(bug_fixes=[_labelled_bug_fix("abc1234", "T1", 11.0, component="mem")]),
    )
    assert MemoryStore.load(path).validate() == []

    bare = _bug_fix("abc1234", "T1", 11.0)
    bare.pop("summary")
    path = _write_document(tmp_path / "state", _document(bug_fixes=[bare]))
    errors = MemoryStore.load(path).validate()
    assert "BUG_FIX_MISSING_FIELD: bug_fixes[0].component is missing" in errors


def test_validate_flags_empty_learning_text(tmp_path: Path) -> None:
    """DOD-06: blank and whitespace-only lesson text are reported."""

    path = _write_document(
        tmp_path / "state",
        _document(learnings=[_learning("L1", "", 12.0)]),
    )
    errors = MemoryStore.load(path).validate()
    assert "LEARNING_MISSING_FIELD: learnings[0].text is missing" in errors

    path = _write_document(
        tmp_path / "state",
        _document(learnings=[_learning("L1", "   ", 12.0)]),
    )
    errors = MemoryStore.load(path).validate()
    assert "LEARNING_MISSING_FIELD: learnings[0].text is missing" in errors


def test_validate_flags_duplicate_brief_keys(tmp_path: Path) -> None:
    """DOD-07: duplicates by ``task_id``/``git_commit``/``at`` name both indices."""

    document = _document(
            runs=[_run("T1", "OK", 10.0), _run("T1", "FAILED", 20.0)],
            bug_fixes=[
                _labelled_bug_fix("abc1234", "T1", 11.0, component="mem"),
                _labelled_bug_fix("abc1234", "T2", 12.0, component="mem"),
            ],
            learnings=[
                _learning("L1", "prefer atomic writes", 13.0),
                _learning("L2", "prune before save", 13.0),
            ],
    )
    # Distinct identity ids: only the REQ-04 brief keys repeat.
    for index, name in enumerate(("run_id", "fix_id")):
        document["runs"][index][name] = f"r{index}"
    document["bug_fixes"][0]["fix_id"] = "f0"
    document["bug_fixes"][1]["fix_id"] = "f1"
    path = _write_document(tmp_path / "state", document)
    errors = MemoryStore.load(path).validate()
    assert any(
        error.startswith("RUN_DUPLICATE_TASK_ID: runs[1].task_id")
        and error.endswith("runs[0]")
        for error in errors
    )
    assert any(
        error.startswith("BUG_FIX_DUPLICATE_GIT_COMMIT: bug_fixes[1].git_commit")
        and error.endswith("bug_fixes[0]")
        for error in errors
    )
    assert any(
        error.startswith("LEARNING_DUPLICATE: learnings[1].at")
        and error.endswith("learnings[0]")
        for error in errors
    )


def test_validate_reports_corrupt_document_as_errors(tmp_path: Path) -> None:
    """DOD-12: a corrupt document is never reported as valid."""

    state = tmp_path / "state"
    state.mkdir(parents=True)
    (state / MEMORY_DOC_NAME).write_text("{not json", encoding="utf-8")
    errors = MemoryStore.load(state / MEMORY_DOC_NAME).validate()
    assert errors
    assert any(error.startswith("MEMORY_CORRUPT:") for error in errors)


def test_validate_enforces_config_limits(tmp_path: Path) -> None:
    """DOD-10: over-limit collections are reported; ``None`` stays silent."""

    path = _write_document(
        tmp_path / "state",
        _document(runs=[_run("T1", "OK", 10.0), _run("T2", "OK", 20.0)]),
    )
    store = MemoryStore.load(path)
    assert store.validate() == []
    assert store.validate(max_runs=5) == []
    errors = store.validate(max_runs=1, max_bug_fixes=0)
    assert "MEMORY_OVER_LIMIT: runs has 2 records, limit is 1" in errors
    assert not any("bug_fixes" in error for error in errors)


def test_validate_does_not_mutate_or_write(tmp_path: Path) -> None:
    """DOD-11: records and file bytes are identical before/after."""

    path = _write_document(
        tmp_path / "state",
        _document(
            runs=[_run("T2", "OK", 30.0), _run("T1", "OK", 10.0)],
            learnings=[_learning("L1", "prefer atomic writes", 12.0)],
        ),
    )
    before_bytes = path.read_bytes()
    store = MemoryStore.load(path)
    before_records = store.records()
    assert store.validate(max_runs=1) != []
    assert store.records() == before_records
    assert path.read_bytes() == before_bytes


def test_cli_validate_memory_reports_valid_document(tmp_path: Path) -> None:
    """DOD-03: one success line, exit 0."""

    root = tmp_path
    state = root / "state"
    _seed(state)
    config = _config(root)
    result = _cli(
        [
            "memory",
            "--validate-memory",
            "--state-root",
            str(state),
            "--config",
            str(config),
            "--maintainer-root",
            str(root),
        ]
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == (
        f"memory is valid: runs=1 bug_fixes=1 learnings=1 "
        f"({state / MEMORY_DOC_NAME})"
    )


def test_cli_validate_memory_reports_errors_and_exits_1(tmp_path: Path) -> None:
    """DOD-08: ``error: N validation problem(s)`` header, per-error lines, exit 1."""

    root = tmp_path
    state = root / "state"
    _write_document(
        state,
        _document(runs=[_run("T2", "OK", 30.0), _run("T1", "OK", 10.0)]),
    )
    config = _config(root)
    result = _cli(
        [
            "memory",
            "--validate-memory",
            "--state-root",
            str(state),
            "--config",
            str(config),
            "--maintainer-root",
            str(root),
        ]
    )
    assert result.returncode == 1, result.stdout
    assert result.stdout == ""
    assert (
        f"error: 1 validation problem(s) in {state / MEMORY_DOC_NAME}"
        in result.stderr
    )
    assert "MEMORY_ORDER: runs[1].at" in result.stderr
    assert "runs[0].at" in result.stderr


def test_cli_validate_memory_without_config_skips_limits(tmp_path: Path) -> None:
    """DOD-13: ``--state-root`` alone is enough; limits skipped with a warning."""

    state = tmp_path / "state"
    _seed(state)
    empty = tmp_path / "no-config"
    empty.mkdir()
    result = _cli(
        [
            "memory",
            "--validate-memory",
            "--state-root",
            str(state),
            "--maintainer-root",
            str(empty),
        ]
    )
    assert result.returncode == 0, result.stderr
    assert "warning: memory limits not checked" in result.stderr
    assert "memory is valid: runs=1 bug_fixes=1 learnings=1" in result.stdout
