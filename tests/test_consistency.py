"""Consistency validation for the maintainer memory store (UTILTOOLS-72).

Covers brief REQ-01 .. REQ-04 (DOD-01 .. DOD-17 in the plan). The validators
are pure functions over record collections, so they are asserted directly; the
store and the engine are exercised through their injected seams (``tmp_path``
document path, injected clock, ``install_memory()``) and no test depends on the
real state root, on the ambient time or on network access.
"""

from __future__ import annotations

import copy
import json
import logging
import os

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.consistency import (
    ERROR_CODES,
    validate_bug_fix_integrity,
    validate_learning_dedup,
    validate_memory_document,
    validate_memory_records,
    validate_run_integrity,
)
from ai_maintainer.engine import MaintainerEngine
from ai_maintainer.memory import MemoryStore as PackageMemoryStore
from ai_maintainer.memory.store import (
    MEMORY_DOC_NAME,
    MEMORY_DOC_VERSION,
    MemoryStore,
)
from ai_maintainer.tasks.curriculum import TaskCurriculum, TaskSpec

CLEAN_DOC = {"version": 1, "runs": [], "bug_fixes": [], "learnings": []}


def _codes(errors: list[str]) -> list[str]:
    """The error codes of ``errors``, in order (``"CODE: detail"`` -> CODE)."""

    return [error.split(":", 1)[0] for error in errors]


def _doc(path):
    return MemoryStore(path)


# --------------------------------------------------------------------------- #
# REQ-01 / DOD-01: the vocabulary and the clean-input contract
# --------------------------------------------------------------------------- #


def test_error_codes_are_the_documented_vocabulary() -> None:
    """DOD-01 / DOD-17: exactly the seven documented codes, in order."""

    assert ERROR_CODES == (
        "MEMORY_CORRUPT",
        "RUN_DUPLICATE_RUN_ID",
        "RUN_MISSING_FIELD",
        "BUG_FIX_DUPLICATE_FIX_ID",
        "BUG_FIX_ORPHAN_TASK",
        "LEARNING_DUPLICATE",
        "LEARNING_MISSING_FIELD",
    )


def test_clean_input_returns_empty_lists() -> None:
    """DOD-01 / REQ-01: clean input yields ``[]`` from every validator."""

    runs = [{"run_id": "r1", "task_id": "a", "state": "COMPLETED"}]
    fixes = [{"fix_id": "f1", "task_id": "a", "summary": "swap sign"}]
    learnings = [{"learning_id": "l1", "text": "prefer injectable clocks"}]

    assert validate_run_integrity(runs) == []
    assert validate_bug_fix_integrity(fixes, known_task_ids={"a", "b"}) == []
    assert validate_bug_fix_integrity(fixes) == []
    assert validate_learning_dedup(learnings) == []
    assert validate_memory_document({"version": 1, "runs": runs,
                                     "bug_fixes": fixes,
                                     "learnings": learnings}) == []
    assert validate_memory_records({"version": 1, "runs": runs,
                                    "bug_fixes": fixes,
                                    "learnings": learnings},
                                   known_task_ids={"a"}) == []


# --------------------------------------------------------------------------- #
# DOD-02: run integrity
# --------------------------------------------------------------------------- #


def test_duplicate_run_id_is_reported_at_the_repeating_index() -> None:
    """DOD-02 / REQ-01: the second occurrence is the offender."""

    runs = [
        {"run_id": "r1", "task_id": "a", "state": "COMPLETED"},
        {"run_id": "r2", "task_id": "b", "state": "PROGRESS"},
        {"run_id": " r1 ", "task_id": "a", "state": "COMPLETED"},
    ]
    errors = validate_run_integrity(runs)
    assert _codes(errors) == ["RUN_DUPLICATE_RUN_ID"]
    assert "runs[2]" in errors[0]
    assert "repeats runs[0]" in errors[0]


def test_missing_run_fields_are_reported_per_field() -> None:
    """DOD-02: a blank or absent id/task/state is one error per field."""

    errors = validate_run_integrity(
        [
            {"run_id": "r1", "task_id": "a", "state": "COMPLETED"},
            {"run_id": "  ", "task_id": "a", "state": "COMPLETED"},
            {"task_id": "b"},
            "not-a-mapping",
        ]
    )
    assert _codes(errors) == ["RUN_MISSING_FIELD"] * 4
    assert errors[0].startswith("RUN_MISSING_FIELD: runs[1].run_id")
    assert "runs[2].run_id" in errors[1]
    assert "runs[2].state" in errors[2]
    assert "runs[3] is not a mapping" in errors[3]


def test_duplicate_run_id_precedes_missing_fields() -> None:
    """DOD-02: report order follows the plan's code table."""

    errors = validate_run_integrity(
        [
            {"run_id": "r1", "task_id": "a", "state": "COMPLETED"},
            {"run_id": "r1"},
        ]
    )
    assert _codes(errors)[0] == "RUN_DUPLICATE_RUN_ID"
    assert set(_codes(errors)) == {"RUN_DUPLICATE_RUN_ID", "RUN_MISSING_FIELD"}


# --------------------------------------------------------------------------- #
# DOD-03: bug-fix integrity
# --------------------------------------------------------------------------- #


def test_duplicate_fix_id_is_reported() -> None:
    """DOD-03 / REQ-01: repeated ``fix_id`` values are duplicates."""

    errors = validate_bug_fix_integrity(
        [
            {"fix_id": "f1", "task_id": "a"},
            {"fix_id": "f1", "task_id": "b"},
        ]
    )
    assert _codes(errors) == ["BUG_FIX_DUPLICATE_FIX_ID"]
    assert "bug_fixes[1].fix_id 'f1' repeats bug_fixes[0]" in errors[0]


def test_orphan_bug_fix_is_reported_against_known_task_ids() -> None:
    """DOD-03: a ``task_id`` outside the known set is an orphan."""

    fixes = [
        {"fix_id": "f1", "task_id": "a"},
        {"fix_id": "f2", "task_id": "ghost"},
        {"fix_id": "f3"},
    ]
    errors = validate_bug_fix_integrity(fixes, known_task_ids={"a", "b"})
    assert _codes(errors) == ["BUG_FIX_ORPHAN_TASK", "BUG_FIX_ORPHAN_TASK"]
    assert "'ghost'" in errors[0]
    assert errors[1].startswith("BUG_FIX_ORPHAN_TASK: bug_fixes[2] has no task_id")

    # Without the authoritative task set the orphan check is skipped.
    assert validate_bug_fix_integrity(fixes) == []


# --------------------------------------------------------------------------- #
# DOD-04: learning dedup
# --------------------------------------------------------------------------- #


def test_learning_duplicates_use_normalised_text() -> None:
    """DOD-04: case-folded, whitespace-collapsed equality is a duplicate."""

    errors = validate_learning_dedup(
        [
            {"learning_id": "l1", "text": "Prefer  injectable clocks"},
            {"learning_id": "l2", "text": " prefer injectable\nclocks "},
        ]
    )
    assert _codes(errors) == ["LEARNING_DUPLICATE"]
    assert "learnings[1].text repeats learnings[0]" in errors[0]


def test_learning_duplicate_by_id_and_missing_text() -> None:
    """DOD-04: a repeated id and an unusable text are both reported."""

    errors = validate_learning_dedup(
        [
            {"learning_id": "l1", "text": "one"},
            {"learning_id": "l1", "text": "two"},
            {"learning_id": "l3", "text": "   "},
            {"learning_id": "l4"},
        ]
    )
    assert _codes(errors) == [
        "LEARNING_DUPLICATE",
        "LEARNING_MISSING_FIELD",
        "LEARNING_MISSING_FIELD",
    ]
    assert "learning_id 'l1'" in errors[0]
    assert errors[1].startswith("LEARNING_MISSING_FIELD: learnings[2].text")


# --------------------------------------------------------------------------- #
# DOD-05: memory document corruption
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "document",
    [
        None,
        [],
        "maintainer_memory.json",
        {"version": 2, "runs": [], "bug_fixes": [], "learnings": []},
        {"runs": [], "bug_fixes": [], "learnings": []},
        {"version": True, "runs": [], "bug_fixes": [], "learnings": []},
        {"version": 1, "runs": {}, "bug_fixes": [], "learnings": []},
        {"version": 1, "runs": [], "bug_fixes": None, "learnings": []},
        {"version": 1, "runs": [], "bug_fixes": [], "learnings": "nope"},
    ],
)
def test_memory_document_corruption_is_flagged(document) -> None:
    """DOD-05 / REQ-01: envelope corruption is ``MEMORY_CORRUPT``."""

    errors = validate_memory_document(document)
    assert errors
    assert set(_codes(errors)) == {"MEMORY_CORRUPT"}


def test_corrupt_envelope_short_circuits_record_checks() -> None:
    """DOD-05: a corrupt document reports the envelope, not a ``TypeError``."""

    errors = validate_memory_records({"version": 9, "runs": None})
    assert set(_codes(errors)) == {"MEMORY_CORRUPT"}
    assert "version 9" in errors[0]
    assert "runs must be a list" in errors[1]
    assert "bug_fixes must be a list" in errors[2]
    assert "learnings must be a list" in errors[3]


# --------------------------------------------------------------------------- #
# DOD-06: edge cases
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("value", [[], (), None, 42, "abc", {}, object()])
def test_record_validators_tolerate_empty_and_non_sequence_input(value) -> None:
    """DOD-06 / REQ-01: ``[]``/``None``/non-sequence is "nothing to check"."""

    assert validate_run_integrity(value) == []
    assert validate_learning_dedup(value) == []
    assert validate_bug_fix_integrity(value) == []
    assert validate_bug_fix_integrity(value, known_task_ids={"a"}) == []


def test_valid_empty_document_is_clean() -> None:
    """DOD-06: a valid document with three empty collections is clean."""

    document = {"version": MEMORY_DOC_VERSION, "runs": [], "bug_fixes": [],
                "learnings": []}
    assert validate_memory_document(document) == []
    assert validate_memory_records(document) == []
    assert validate_memory_records(document, known_task_ids={"a", "b"}) == []


# --------------------------------------------------------------------------- #
# DOD-07: determinism, read-only behaviour, vocabulary closure
# --------------------------------------------------------------------------- #


def test_validators_are_deterministic_and_read_only() -> None:
    """DOD-07: same input, same list; the input is never mutated."""

    runs = [
        {"run_id": "r1", "task_id": "a", "state": "COMPLETED"},
        {"run_id": "r1", "task_id": "a", "state": "COMPLETED"},
    ]
    fixes = [{"fix_id": "f1", "task_id": "ghost"}, {"fix_id": "f1"}]
    learnings = [{"learning_id": "l1", "text": "one"},
                 {"learning_id": "l1", "text": " one "}]
    snapshot = copy.deepcopy((runs, fixes, learnings))

    first = (
        validate_run_integrity(runs),
        validate_bug_fix_integrity(fixes, known_task_ids={"a"}),
        validate_learning_dedup(learnings),
    )
    second = (
        validate_run_integrity(runs),
        validate_bug_fix_integrity(fixes, known_task_ids={"a"}),
        validate_learning_dedup(learnings),
    )
    assert first == second
    assert (runs, fixes, learnings) == snapshot


def test_every_emitted_error_uses_the_closed_vocabulary() -> None:
    """DOD-17: no code outside ``ERROR_CODES`` is ever emitted."""

    emitted: list[str] = []
    emitted += validate_run_integrity([{"run_id": "r1"}, "x",
                                       {"run_id": "r1", "task_id": "a",
                                        "state": "S"}])
    emitted += validate_bug_fix_integrity(
        [{"fix_id": "f1", "task_id": "ghost"}, {"fix_id": "f1"}],
        known_task_ids={"a"},
    )
    emitted += validate_learning_dedup([{}, {"text": "x"}, {"text": "X"}])
    emitted += validate_memory_document({"version": 3})
    assert emitted
    for code in _codes(emitted):
        assert code in ERROR_CODES
    for error in emitted:
        assert ": " in error


# --------------------------------------------------------------------------- #
# DOD-08: loading a damaged document degrades, never raises
# --------------------------------------------------------------------------- #


def test_missing_document_loads_as_an_empty_clean_store(tmp_path) -> None:
    """DOD-08: a missing file is a fresh store, not an issue."""

    store = MemoryStore.load(tmp_path / MEMORY_DOC_NAME)
    assert store.issues() == []
    assert store.records() == {"version": MEMORY_DOC_VERSION, "runs": [],
                               "bug_fixes": [], "learnings": []}


@pytest.mark.parametrize(
    "content",
    [
        b"{ this is not json",
        b"[]",
        b'{"version": 2, "runs": [], "bug_fixes": [], "learnings": []}',
        b'{"version": 1, "runs": null, "bug_fixes": [], "learnings": []}',
    ],
)
def test_corrupt_document_loads_as_an_empty_store_with_issue(
    tmp_path, content
) -> None:
    """DOD-08 / REQ-02: corruption surfaces as ``MEMORY_CORRUPT``."""

    path = tmp_path / MEMORY_DOC_NAME
    path.write_bytes(content)
    store = MemoryStore.load(path)
    assert set(_codes(store.issues())) == {"MEMORY_CORRUPT"}
    assert store.records()["runs"] == []


def test_oversized_document_is_not_read(tmp_path) -> None:
    """DOD-08: the load is bounded by a byte cap."""

    path = tmp_path / MEMORY_DOC_NAME
    path.write_bytes(b"{" + b" " * 70000 + b"}")
    store = MemoryStore.load(path)
    assert "larger than" in store.issues()[0]
    assert _codes(store.issues()) == ["MEMORY_CORRUPT"]


def test_symlinked_document_is_refused_on_load_and_save(tmp_path) -> None:
    """DOD-08 / DOD-09: a symlinked document is never read or written through."""

    real = tmp_path / "elsewhere.json"
    real.write_text(json.dumps({"version": 1, "runs": [{"run_id": "r1"}],
                                "bug_fixes": [], "learnings": []}), encoding="utf-8")
    link = tmp_path / MEMORY_DOC_NAME
    os.symlink(real, link)

    store = MemoryStore.load(link)
    assert _codes(store.issues()) == ["MEMORY_CORRUPT"]
    assert store.records()["runs"] == []

    before = real.read_bytes()
    store.add_learning({"learning_id": "l1", "text": "kept out of the link target"})
    assert store.save() == []
    assert real.read_bytes() == before
    assert link.is_symlink()
    assert not (tmp_path / (MEMORY_DOC_NAME + ".tmp")).exists()


@pytest.mark.parametrize("factory", ["directory", "fifo"])
def test_non_regular_document_is_ignored(tmp_path, factory) -> None:
    """DOD-08: a directory or FIFO at the path is never opened."""

    path = tmp_path / MEMORY_DOC_NAME
    if factory == "directory":
        path.mkdir()
    else:
        if not hasattr(os, "mkfifo"):  # pragma: no cover - platform guard
            pytest.skip("no os.mkfifo on this platform")
        os.mkfifo(path)

    store = MemoryStore.load(path)
    assert _codes(store.issues()) == ["MEMORY_CORRUPT"]
    assert "not a regular file" in store.issues()[0]


# --------------------------------------------------------------------------- #
# DOD-09 / DOD-10: atomic save and round trip
# --------------------------------------------------------------------------- #


def test_save_is_atomic_and_leaves_no_temporary_file(tmp_path) -> None:
    """DOD-09: tmp sibling + ``os.replace``, no leftovers, not executable."""

    path = tmp_path / MEMORY_DOC_NAME
    store = MemoryStore(path)
    store.add_run({"run_id": "r1", "task_id": "a", "state": "PROGRESS"})
    assert store.save() == []

    assert path.exists()
    assert not (tmp_path / (MEMORY_DOC_NAME + ".tmp")).exists()
    mode = os.stat(path).st_mode
    assert mode & 0o600 == 0o600
    assert not mode & 0o111
    document = json.loads(path.read_text(encoding="utf-8"))
    assert document["version"] == MEMORY_DOC_VERSION
    assert document["runs"][0]["run_id"] == "r1"
    assert isinstance(document["runs"][0]["at"], float)


def test_save_load_round_trip_preserves_all_three_collections(tmp_path) -> None:
    """DOD-10 / REQ-02: what ``save()`` writes is what ``load()`` returns."""

    path = tmp_path / MEMORY_DOC_NAME
    store = MemoryStore(path)
    store.add_run({"run_id": "r1", "task_id": "a", "state": "COMPLETED"})
    store.add_bug_fix({"fix_id": "f1", "task_id": "a", "summary": "off-by-one"})
    store.add_learning({"learning_id": "l1", "text": "inject the clock"})
    assert store.save(known_task_ids={"a"}) == []

    reloaded = MemoryStore.load(path)
    assert reloaded.issues() == []
    assert [record["run_id"] for record in reloaded.records()["runs"]] == ["r1"]
    assert [record["fix_id"] for record in reloaded.records()["bug_fixes"]] == ["f1"]
    assert [record["learning_id"] for record in reloaded.records()["learnings"]] == [
        "l1"
    ]
    assert reloaded.records() == store.records()


def test_records_is_a_defensive_copy(tmp_path) -> None:
    """DOD-10: mutating the returned document never reaches the store."""

    store = MemoryStore(tmp_path / MEMORY_DOC_NAME)
    store.add_run({"run_id": "r1", "task_id": "a", "state": "COMPLETED"})
    document = store.records()
    document["runs"][0]["run_id"] = "mutated"
    document["runs"].append({"run_id": "r2", "task_id": "a", "state": "PROGRESS"})
    assert [record["run_id"] for record in store.records()["runs"]] == ["r1"]


# --------------------------------------------------------------------------- #
# DOD-11 / DOD-12: save-pipeline logging
# --------------------------------------------------------------------------- #


def test_save_logs_one_warning_per_issue_and_still_writes(tmp_path, caplog) -> None:
    """DOD-11 / REQ-02: warnings, and persistence is not blocked."""

    path = tmp_path / MEMORY_DOC_NAME
    store = MemoryStore(path)
    store.add_bug_fix({"fix_id": "f1", "task_id": "ghost"})
    store.add_learning({"learning_id": "l1", "text": "same"})
    store.add_learning({"learning_id": "l2", "text": " SAME "})

    with caplog.at_level(logging.WARNING, logger="ai_maintainer.memory.store"):
        errors = store.save(known_task_ids={"a"})

    assert _codes(errors) == ["BUG_FIX_ORPHAN_TASK", "LEARNING_DUPLICATE"]
    warnings = [record for record in caplog.records
                if record.levelno == logging.WARNING]
    assert len(warnings) == 2
    assert warnings[0].getMessage() == errors[0]
    assert warnings[1].getMessage() == errors[1]
    assert store.issues() == errors
    assert path.exists()
    assert json.loads(path.read_text(encoding="utf-8"))["bug_fixes"]


def test_clean_save_emits_no_warning(tmp_path, caplog) -> None:
    """DOD-12: a clean document is silent."""

    store = MemoryStore(tmp_path / MEMORY_DOC_NAME)
    store.add_run({"run_id": "r1", "task_id": "a", "state": "COMPLETED"})
    with caplog.at_level(logging.WARNING, logger="ai_maintainer.memory.store"):
        assert store.save(known_task_ids={"a"}) == []
    assert [record for record in caplog.records
            if record.levelno == logging.WARNING] == []
    assert store.issues() == []


def test_unserialisable_record_is_logged_not_raised(tmp_path, caplog) -> None:
    """DOD-16: a write failure is logged and swallowed, the cycle survives."""

    store = MemoryStore(tmp_path / MEMORY_DOC_NAME)
    store.add_run({"run_id": "r1", "task_id": "a", "state": "COMPLETED",
                   "payload": object()})
    with caplog.at_level(logging.WARNING, logger="ai_maintainer.memory.store"):
        assert store.save() == []
    assert any("write failed" in record.getMessage() for record in caplog.records)
    assert not (tmp_path / MEMORY_DOC_NAME).exists()
    assert not (tmp_path / (MEMORY_DOC_NAME + ".tmp")).exists()


# --------------------------------------------------------------------------- #
# DOD-13 / DOD-14: engine integration
# --------------------------------------------------------------------------- #


def _engine(memory=None) -> MaintainerEngine:
    specs = [TaskSpec(name="a", steps_required=1, last_proposed_cycle=100)]
    config = MaintainerConfig()
    curriculum = TaskCurriculum(specs=specs, config=config)
    return MaintainerEngine(curriculum, config=config, memory=memory)


def _frozen_clock():
    now = [100.0]

    def clock() -> float:
        return now[0]

    return clock


def test_finish_cycle_runs_after_records_and_before_the_report(
    tmp_path, monkeypatch
) -> None:
    """DOD-13 / REQ-03: memory is saved inside the cycle, before the report."""

    store = MemoryStore(tmp_path / MEMORY_DOC_NAME)
    store.add_bug_fix({"fix_id": "f1", "task_id": "ghost"})
    engine = _engine(memory=store)

    calls: list[str] = []
    original = MemoryStore.save

    def spy(self, *, known_task_ids=None):
        calls.append("save")
        assert [event["event"] for event in engine.events][-1] == "COMPLETED"
        return original(self, known_task_ids=known_task_ids)

    monkeypatch.setattr(MemoryStore, "save", spy)
    result = engine.run_cycle()

    assert calls == ["save"]
    assert result.cycle == 1
    assert _codes(engine._last_cycle_issues) == ["BUG_FIX_ORPHAN_TASK"]
    last = engine.events[-1]
    assert last["event"] == "CONSISTENCY"
    assert last["kind"] == "CONSISTENCY"
    assert last["codes"] == engine._last_cycle_issues
    assert last["cycle"] == 1
    assert (tmp_path / MEMORY_DOC_NAME).exists()


def test_engine_without_memory_behaves_as_before() -> None:
    """DOD-14: the default engine path is unchanged (no store, no event)."""

    engine = _engine()
    result = engine.run_cycle()
    assert result.cycle == 1
    assert engine._last_cycle_issues == []
    assert [event["event"] for event in engine.events] == ["COMPLETED"]


def test_install_memory_seam_takes_effect_on_the_next_cycle(tmp_path) -> None:
    """DOD-14: ``install_memory()`` is the injection seam (``None`` removes)."""

    engine = _engine()
    store = MemoryStore(tmp_path / MEMORY_DOC_NAME)
    engine.install_memory(store)
    engine.run_cycle()
    assert engine._last_cycle_issues == []
    assert [event["event"] for event in engine.events][-1] == "CONSISTENCY"
    assert (tmp_path / MEMORY_DOC_NAME).exists()

    engine.install_memory(None)
    before = [event["event"] for event in engine.events]
    engine.run_cycle()
    assert engine._last_cycle_issues == []
    assert [event["event"] for event in engine.events] == before
    assert before.count("CONSISTENCY") == 1


def test_engine_cycle_survives_a_raising_memory_store(tmp_path) -> None:
    """DOD-16 / REQ-03: a broken store never fails a cycle."""

    class ExplodingStore:
        def save(self, *, known_task_ids=None):
            raise RuntimeError("disk on fire")

    engine = _engine()
    engine.install_memory(ExplodingStore())
    result = engine.run_cycle()
    assert result.cycle == 1
    assert _codes(engine._last_cycle_issues) == ["MEMORY_CORRUPT"]
    assert engine.events[-1]["event"] == "CONSISTENCY"


def test_package_reexports_the_store() -> None:
    """DOD-04 of the plan / REQ-02: ``ai_maintainer.memory`` re-exports."""

    assert PackageMemoryStore is MemoryStore
