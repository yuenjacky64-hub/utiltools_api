"""Structural validation of the maintainer memory document (UTILTOOLS-82).

Covers brief REQ-01 .. REQ-03 (DOD-01 .. DOD-08 in the plan). Every test drives
the store through ``MemoryStore.load(tmp_path / MEMORY_DOC_NAME)`` with an
injected clock; nothing depends on the real state root, on ambient time or on
network access.
"""

from __future__ import annotations

import json
import logging
import os
import stat

import pytest

from ai_maintainer.memory.store import (
    MEMORY_DOC_NAME,
    MEMORY_DOC_VERSION,
    MemoryStore,
)

CLEAN_DOC = {"version": 1, "runs": [], "bug_fixes": [], "learnings": []}
STAMP = 1_700_000_000
LOG = "ai_maintainer.memory.store"


def _write(path, document, mode: int | None = None) -> bytes:
    """Write ``document`` as UTF-8 JSON at ``path``; return the bytes written."""

    payload = (
        document if isinstance(document, bytes)
        else json.dumps(document).encode("utf-8")
    )
    path.write_bytes(payload)
    if mode is not None:
        os.chmod(path, mode)
    return payload


def _codes(issues: list[str]) -> list[str]:
    return [issue.split(":", 1)[0] for issue in issues]


def _siblings(directory, name: str = MEMORY_DOC_NAME) -> list[str]:
    """Every entry beside the document whose name starts with ``name``."""

    return sorted(entry.name for entry in directory.iterdir()
                  if entry.name.startswith(name))


# --------------------------------------------------------------------------- #
# DOD-01 / DOD-02: structural defects degrade to the empty state and quarantine
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "document",
    [
        {"version": 1, "bug_fixes": [], "learnings": []},                 # no runs
        {"version": 1, "runs": None, "bug_fixes": [], "learnings": []},   # runs null
        {"version": 1, "runs": {}, "bug_fixes": [], "learnings": []},      # runs dict
        {"version": 1},                                                   # only version
    ],
)
def test_structurally_invalid_document_loads_empty_and_is_quarantined(
    tmp_path, document
) -> None:
    """DOD-01 / REQ-01: bad shape -> empty store, MEMORY_CORRUPT, renamed file."""

    path = tmp_path / MEMORY_DOC_NAME
    _write(path, document)
    store = MemoryStore.load(path, clock=lambda: STAMP)

    assert _codes(store.issues()) == ["MEMORY_CORRUPT"] * len(store.issues())
    assert store.records() == {"version": MEMORY_DOC_VERSION, "runs": [],
                               "bug_fixes": [], "learnings": []}
    assert store.validate()  # loaded_ok is false: validity stays unknown
    quarantined = store.quarantined_path()
    assert quarantined is not None
    assert quarantined.endswith(f"{MEMORY_DOC_NAME}.corrupt-{STAMP}")
    assert not path.exists()


def test_missing_runs_key_quarantines_the_document(tmp_path) -> None:
    """DOD-02 / REQ-01: the evidence file keeps the bytes, mode 0o600."""

    path = tmp_path / MEMORY_DOC_NAME
    payload = _write(
        path,
        {"version": 1, "bug_fixes": [], "learnings": []},
        mode=0o644,
    )

    store = MemoryStore.load(path, clock=lambda: STAMP)

    assert store.records()["runs"] == []
    assert store.records()["bug_fixes"] == []
    assert store.records()["learnings"] == []
    assert "is missing the 'runs' list" in store.issues()[0]

    quarantined = store.quarantined_path()
    assert quarantined is not None
    moved = tmp_path / os.path.basename(quarantined)
    assert moved.exists()
    assert not path.exists()
    assert moved.read_bytes() == payload
    assert stat.S_IMODE(moved.stat().st_mode) == 0o600


# --------------------------------------------------------------------------- #
# DOD-03 / DOD-04: the other two collections, each with its own message
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    ("document", "expected"),
    [
        (
            {"version": 1, "runs": [], "learnings": []},
            "is missing the 'bug_fixes' list",
        ),
        (
            {"version": 1, "runs": [], "bug_fixes": None, "learnings": []},
            "field 'bug_fixes' must be a list (got NoneType)",
        ),
    ],
)
def test_missing_or_mistyped_bug_fixes_quarantines(
    tmp_path, document, expected
) -> None:
    """DOD-03 / REQ-02: ``bug_fixes`` is validated and named in the issue."""

    path = tmp_path / MEMORY_DOC_NAME
    _write(path, document)
    store = MemoryStore.load(path, clock=lambda: STAMP)

    assert store.issues() == [f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} {expected}"]
    assert store.records()["bug_fixes"] == []
    assert store.quarantined_path() is not None
    assert not path.exists()


@pytest.mark.parametrize(
    ("document", "expected"),
    [
        (
            {"version": 1, "runs": [], "bug_fixes": []},
            "is missing the 'learnings' list",
        ),
        (
            {"version": 1, "runs": [], "bug_fixes": [], "learnings": "nope"},
            "field 'learnings' must be a list (got str)",
        ),
    ],
)
def test_missing_or_mistyped_learnings_quarantines(
    tmp_path, document, expected
) -> None:
    """DOD-04 / REQ-03: ``learnings`` (never ``learnnings``) is named."""

    path = tmp_path / MEMORY_DOC_NAME
    _write(path, document)
    store = MemoryStore.load(path, clock=lambda: STAMP)

    assert store.issues() == [f"MEMORY_CORRUPT: {MEMORY_DOC_NAME} {expected}"]
    assert "learnnings" not in store.issues()[0]
    assert store.records()["learnings"] == []
    assert store.quarantined_path() is not None
    assert not path.exists()


# --------------------------------------------------------------------------- #
# DOD-05: the quarantine boundary (INV-STR-2)
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    ("payload", "expected"),
    [
        (b'{"version": 2, "runs": [], "bug_fixes": [], "learnings": []}',
         "unsupported document version 2"),
        (b"[]", "document root must be a mapping"),
        (b"{ this is not json", "is not valid JSON"),
        (
            b'{"version": 1, "runs": [{"run_id": "r1"}], "bug_fixes": [],'
            b' "learnings": []}',
            "RUN_MISSING_FIELD",
        ),
    ],
)
def test_non_structural_defects_are_not_quarantined(
    tmp_path, payload, expected
) -> None:
    """DOD-05 / REQ-01: wrong version, bad root, bad JSON, record defects stay."""

    path = tmp_path / MEMORY_DOC_NAME
    _write(path, payload)
    store = MemoryStore.load(path, clock=lambda: STAMP)

    assert any(expected in issue for issue in store.issues())
    assert store.quarantined_path() is None
    assert path.exists()
    assert path.read_bytes() == payload
    assert _siblings(tmp_path) == [MEMORY_DOC_NAME]


def test_non_regular_document_is_not_quarantined(tmp_path) -> None:
    """DOD-05 / REQ-01: a directory at the path is never renamed."""

    path = tmp_path / MEMORY_DOC_NAME
    path.mkdir()
    store = MemoryStore.load(path, clock=lambda: STAMP)

    assert "is not a regular file" in store.issues()[0]
    assert store.quarantined_path() is None
    assert path.is_dir()
    assert _siblings(tmp_path) == [MEMORY_DOC_NAME]


# --------------------------------------------------------------------------- #
# DOD-06 / DOD-07: a clean load writes nothing; quarantine + save coexist
# --------------------------------------------------------------------------- #


def test_clean_load_writes_nothing(tmp_path) -> None:
    """DOD-06 / REQ-02: the read path stays read-only (INV-STR-4)."""

    path = tmp_path / MEMORY_DOC_NAME
    payload = _write(
        path,
        {"version": 1, "runs": [{"run_id": "r1", "task_id": "T-1",
                                 "state": "done"}],
         "bug_fixes": [], "learnings": []},
    )
    before = _siblings(tmp_path)

    store = MemoryStore.load(path, clock=lambda: STAMP)

    assert store.issues() == []
    assert store.quarantined_path() is None
    assert store.records()["runs"][0]["run_id"] == "r1"
    assert _siblings(tmp_path) == before
    assert path.read_bytes() == payload


def test_quarantine_preserves_bytes_and_uses_unique_names(tmp_path) -> None:
    """DOD-07 / REQ-03: evidence survives, ``save()`` starts fresh, names unique."""

    path = tmp_path / MEMORY_DOC_NAME
    first = _write(path, {"version": 1, "bug_fixes": [], "learnings": []})
    store = MemoryStore.load(path, clock=lambda: STAMP)
    first_quarantine = store.quarantined_path()
    assert first_quarantine is not None

    assert store.save() == []
    written = json.loads(path.read_text(encoding="utf-8"))
    assert written == {"version": MEMORY_DOC_VERSION, "runs": [],
                       "bug_fixes": [], "learnings": []}
    assert (tmp_path / os.path.basename(first_quarantine)).read_bytes() == first

    second = _write(path, {"version": 1, "runs": {}, "bug_fixes": [],
                           "learnings": []})
    store = MemoryStore.load(path, clock=lambda: STAMP)
    second_quarantine = store.quarantined_path()
    assert second_quarantine is not None
    assert second_quarantine != first_quarantine
    assert second_quarantine.endswith(f"-1")
    assert (tmp_path / os.path.basename(second_quarantine)).read_bytes() == second
    assert (tmp_path / os.path.basename(first_quarantine)).read_bytes() == first


# --------------------------------------------------------------------------- #
# DOD-08: a failed quarantine never breaks the load (INV-STR-1)
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("failure", ["replace", "unwritable"])
def test_quarantine_failure_is_logged_not_raised(
    tmp_path, monkeypatch, caplog, failure
) -> None:
    """DOD-08 / REQ-01: an ``OSError`` in the rename is logged and swallowed."""

    if failure == "unwritable" and os.geteuid() == 0:  # pragma: no cover
        pytest.skip("root ignores directory permissions")

    path = tmp_path / MEMORY_DOC_NAME
    payload = _write(path, {"version": 1, "runs": None, "bug_fixes": [],
                            "learnings": []})

    if failure == "replace":
        def _raise(*args, **kwargs):
            raise OSError("rename refused")

        monkeypatch.setattr(os, "replace", _raise)
    else:
        os.chmod(tmp_path, 0o500)

    try:
        with caplog.at_level(logging.WARNING, logger=LOG):
            store = MemoryStore.load(path, clock=lambda: STAMP)
    finally:
        if failure == "unwritable":
            os.chmod(tmp_path, 0o700)

    assert _codes(store.issues()) == ["MEMORY_CORRUPT"]
    assert "must be a list (got NoneType)" in store.issues()[0]
    assert store.quarantined_path() is None
    assert store.records()["runs"] == []
    assert path.exists()
    assert path.read_bytes() == payload
    assert [record.name for record in caplog.records if record.name == LOG] == [LOG]
    assert "quarantine" in caplog.records[-1].getMessage()
