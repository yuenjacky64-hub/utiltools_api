"""Cross-process safety of the maintainer memory document (UTILTOOLS-98).

Covers the brief of UTILTOOLS-98 (plan REQ-01 .. REQ-07, DOD-01 .. DOD-11).
Every test drives :class:`MemoryStore` against a document under ``tmp_path``;
the process-level tests use ``fork`` so a child shares nothing but the file,
which is exactly the daemon/CLI split the change is about.
"""

from __future__ import annotations

import json
import multiprocessing
import os
from pathlib import Path

import pytest

from ai_maintainer.memory.store import (
    KNOWLEDGE_BASE_DOC_NAME,
    MEMORY_DOC_NAME,
    MEMORY_DOC_VERSION,
    MEMORY_KB_RENDER_FAILED,
    MemoryStore,
)

COLLECTIONS = ("runs", "bug_fixes", "learnings")
CORRUPT_DOC = {"version": 1, "runs": {}, "bug_fixes": [], "learnings": []}


def _context():
    """A ``fork`` context, or a skip on a platform without one."""

    try:
        return multiprocessing.get_context("fork")
    except ValueError:  # pragma: no cover - non-POSIX
        pytest.skip("cross-process tests need a fork-capable platform")


def _write_doc(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document), encoding="utf-8")


def _documents_by_id(store: MemoryStore) -> dict[str, list[str]]:
    return {name: store.entry_ids(name) for name in COLLECTIONS}


def _keys(store: MemoryStore) -> dict[str, list[str]]:
    """The three collections' identifying field values, for readability checks."""

    records = store.records()
    return {
        "run_id": [record["run_id"] for record in records["runs"]],
        "fix_id": [record["fix_id"] for record in records["bug_fixes"]],
        "learning_id": [record["learning_id"] for record in records["learnings"]],
    }


# --- DOD-01 / REQ-01: entry ids assigned at record time --------------------


def test_entry_id_assigned_at_record_time(tmp_path: Path) -> None:
    """DOD-01: every record has a non-blank id the moment it is recorded."""

    path = tmp_path / MEMORY_DOC_NAME
    store = MemoryStore(path, clock=lambda: 100.0)
    store.record_run({"run_id": "r1", "task_id": "a", "state": "COMPLETED"})
    store.record_bug_fix({"fix_id": "f1", "task_id": "a", "summary": "off-by-one"})
    store.record_learning({"learning_id": "l1", "text": "inject the clock"})

    fresh = _documents_by_id(store)
    for name in COLLECTIONS:
        assert len(fresh[name]) == 1, name
        assert fresh[name][0].strip(), name
    # No save has happened yet: the ids exist before the document does.
    assert not path.exists()

    assert store.save(known_task_ids={"a"}) == []
    reloaded = MemoryStore.load(path)
    assert _documents_by_id(reloaded) == fresh

    # A caller-supplied id always wins (the import path mints ids elsewhere).
    pinned = MemoryStore(tmp_path / "pinned" / MEMORY_DOC_NAME)
    pinned.record_learning({"learning_id": "l2", "text": "pin me",
                            "entry_id": "pinned-id"})
    assert pinned.entry_ids("learnings") == ["pinned-id"]


# --- DOD-02 / REQ-01: legacy documents get deterministic ids --------------


def test_legacy_records_get_deterministic_entry_ids(tmp_path: Path) -> None:
    """DOD-02: a document without ids maps to the same ids on every load."""

    path = tmp_path / MEMORY_DOC_NAME
    _write_doc(
        path,
        {
            "version": MEMORY_DOC_VERSION,
            "runs": [
                {"run_id": "r1", "task_id": "a", "state": "COMPLETED", "at": 1.0},
                {"run_id": "r2", "task_id": "b", "state": "COMPLETED", "at": 2.0},
            ],
            "bug_fixes": [
                {"fix_id": "f1", "task_id": "a", "summary": "same", "at": 1.0},
                {"fix_id": "f2", "task_id": "a", "summary": "same", "at": 1.4},
            ],
            "learnings": [],
        },
    )

    first = _documents_by_id(MemoryStore.load(path))
    second = _documents_by_id(MemoryStore.load(path))
    assert first == second
    assert len(set(first["runs"]) | set(first["bug_fixes"])) == 4
    for name in COLLECTIONS:
        for entry_id in first[name]:
            assert entry_id.startswith("e-") and len(entry_id) == 26
    # A copy of the same document elsewhere yields the same ids: the mapping
    # depends on content, not on where or when it was loaded.
    copy = tmp_path / "copy" / MEMORY_DOC_NAME
    copy.parent.mkdir()
    copy.write_bytes(path.read_bytes())
    assert _documents_by_id(MemoryStore.load(copy)) == first

    # Saving and reloading keeps the mapping: the ids are content-derived, so
    # the merge cannot see the same legacy record as a new entry.
    assert MemoryStore.load(path).save() == []
    assert _documents_by_id(MemoryStore.load(path)) == first


# --- DOD-03 / REQ-02, REQ-03: two writer processes ------------------------


def _writer(path: str, barrier, index: int) -> None:
    """Record this writer's entries, saving after every one of them."""

    store = MemoryStore.load(path)
    barrier.wait(timeout=30)
    task = f"task-{index}"
    for i in range(3):
        store.record_run({"run_id": f"w{index}-r{i}", "task_id": task,
                          "state": "COMPLETED"})
        store.save(known_task_ids={task})
        store.record_bug_fix({"fix_id": f"w{index}-f{i}", "task_id": task,
                              "summary": f"fix {index}/{i}"})
        store.save(known_task_ids={task})
        store.record_learning({"learning_id": f"w{index}-l{i}",
                               "text": f"lesson {index}/{i}"})
        store.save(known_task_ids={task})


def test_two_processes_at_barrier_keep_every_entry_exactly_once(
    tmp_path: Path,
) -> None:
    """DOD-03: no interleaving of two writers loses or duplicates an entry."""

    ctx = _context()
    path = tmp_path / MEMORY_DOC_NAME
    barrier = ctx.Barrier(2)
    writers = 2
    processes = [
        ctx.Process(target=_writer, args=(str(path), barrier, index))
        for index in range(writers)
    ]
    for process in processes:
        process.start()
    for process in processes:
        process.join(timeout=120)
        assert process.exitcode == 0

    reloaded = MemoryStore.load(path)
    records = reloaded.records()
    per_writer = writers * 3
    for name in COLLECTIONS:
        assert len(records[name]) == per_writer, name
    assert sorted(_keys(reloaded)["run_id"]) == sorted(
        f"w{index}-r{i}" for index in range(writers) for i in range(3)
    )
    assert sorted(_keys(reloaded)["learning_id"]) == sorted(
        f"w{index}-l{i}" for index in range(writers) for i in range(3)
    )
    for name in COLLECTIONS:
        ids = reloaded.entry_ids(name)
        assert len(set(ids)) == len(ids) == per_writer, name


# --- DOD-04 / REQ-02: knowledge base matches the merged document ----------


def test_knowledge_base_matches_merged_document(tmp_path: Path) -> None:
    """DOD-04: the rendering is regenerated inside the save, from the merge."""

    path = tmp_path / MEMORY_DOC_NAME
    first = MemoryStore(path, clock=lambda: 10.0)
    first.record_run({"run_id": "r-local", "task_id": "a", "state": "COMPLETED"})
    assert first.save(known_task_ids={"a"}) == []

    # A peer that never saw the first writer's run writes its own entry: the
    # rendering must then carry both, not just the peer's snapshot.
    peer = MemoryStore.load(path)
    peer.record_bug_fix({"fix_id": "f-peer", "task_id": "a", "summary": "peer fix"})
    assert peer.save(known_task_ids={"a"}) == []

    kb_path = path.with_name(KNOWLEDGE_BASE_DOC_NAME)
    rendered = kb_path.read_text(encoding="utf-8")
    assert "r-local" in rendered
    assert "f-peer" in rendered
    document = json.loads(path.read_text(encoding="utf-8"))
    # Re-rendering the unchanged document reproduces the bytes exactly.
    assert peer._render_knowledge_base(document) == rendered
    assert peer.issues() == []
    assert MEMORY_KB_RENDER_FAILED not in rendered


# --- DOD-05 / REQ-03: stale snapshots -------------------------------------


def test_stale_snapshots_do_not_lose_or_duplicate_entries(tmp_path: Path) -> None:
    """DOD-05: two stores from the same file, interleaved, lose nothing."""

    path = tmp_path / MEMORY_DOC_NAME
    _write_doc(
        path,
        {"version": MEMORY_DOC_VERSION, "runs": [], "bug_fixes": [],
         "learnings": []},
    )
    left = MemoryStore.load(path)
    right = MemoryStore.load(path)

    left.record_run({"run_id": "r-left", "task_id": "a", "state": "COMPLETED"})
    assert left.save(known_task_ids={"a"}) == []
    right.record_learning({"learning_id": "l-right", "text": "from the right"})
    assert right.save() == []
    left.record_bug_fix({"fix_id": "f-left", "task_id": "a", "summary": "left"})
    assert left.save(known_task_ids={"a"}) == []
    right.record_run({"run_id": "r-right", "task_id": "b", "state": "COMPLETED"})
    assert right.save(known_task_ids={"a", "b"}) == []

    reloaded = MemoryStore.load(path)
    keys = _keys(reloaded)
    assert sorted(keys["run_id"]) == ["r-left", "r-right"]
    assert keys["fix_id"] == ["f-left"]
    assert keys["learning_id"] == ["l-right"]
    for name in COLLECTIONS:
        ids = reloaded.entry_ids(name)
        assert len(set(ids)) == len(ids), name
    # The last writer adopted the merged snapshot instead of its stale one...
    assert _keys(right)["run_id"] == ["r-left", "r-right"]
    assert _keys(right)["fix_id"] == ["f-left"]
    # ...and a writer that has not saved since a peer's write still holds only
    # what it last merged, never a resurrected copy of a pruned record.
    assert _keys(left)["run_id"] == ["r-left"]


# --- DOD-06 / REQ-03: a prune is not resurrected --------------------------


def test_prune_is_not_resurrected_by_stale_snapshot(tmp_path: Path) -> None:
    """DOD-06: a stale snapshot cannot bring back a peer's pruned records."""

    path = tmp_path / MEMORY_DOC_NAME
    _write_doc(
        path,
        {
            "version": MEMORY_DOC_VERSION,
            "runs": [
                {"run_id": f"old-{index}", "task_id": "a", "state": "COMPLETED",
                 "at": float(index)}
                for index in range(5)
            ],
            "bug_fixes": [],
            "learnings": [],
        },
    )
    pruner = MemoryStore.load(path)
    stale = MemoryStore.load(path)

    assert pruner.prune(max_runs=2) == {"runs": 3, "bug_fixes": 0, "learnings": 0}
    assert pruner.save(known_task_ids={"a"}) == []

    stale.record_run({"run_id": "new-stale", "task_id": "b", "state": "COMPLETED"})
    assert stale.save(known_task_ids={"b"}) == []

    reloaded = MemoryStore.load(path)
    assert sorted(_keys(reloaded)["run_id"]) == ["new-stale", "old-3", "old-4"]
    assert stale.pending_count() == 0


# --- DOD-07 / REQ-04: exactly-once retries --------------------------------


def test_failed_write_keeps_pending_and_retries_once(
    tmp_path: Path, monkeypatch
) -> None:
    """DOD-07: a failed write/render keeps the record pending, saved once."""

    path = tmp_path / MEMORY_DOC_NAME
    store = MemoryStore(path, clock=lambda: 5.0)
    store.record_run({"run_id": "r1", "task_id": "a", "state": "COMPLETED"})

    def _explode(*args, **kwargs):
        raise OSError("disk is gone")

    with monkeypatch.context() as patch:
        patch.setattr(MemoryStore, "_write", _explode)
        assert store.save(known_task_ids={"a"}) == []
    assert store.pending_count() == 1
    assert not path.exists()
    assert not path.with_name(path.name + ".tmp").exists()

    # Retry: the pending record lands exactly once.
    assert store.save(known_task_ids={"a"}) == []
    assert store.pending_count() == 0
    assert _keys(MemoryStore.load(path))["run_id"] == ["r1"]

    # A second save of the same (now empty) pending set cannot duplicate it.
    assert store.save(known_task_ids={"a"}) == []
    assert _keys(MemoryStore.load(path))["run_id"] == ["r1"]

    store.record_learning({"learning_id": "l1", "text": "keep me"})
    kb_path = path.with_name(KNOWLEDGE_BASE_DOC_NAME)

    def _no_render(*args, **kwargs):
        raise ValueError("render exploded")

    with monkeypatch.context() as patch:
        patch.setattr(MemoryStore, "_render_knowledge_base", _no_render)
        issues = store.save()
    assert any(MEMORY_KB_RENDER_FAILED in issue for issue in issues)
    assert store.pending_count() == 1
    # The document itself is complete; only the rendering is missing.
    assert _keys(MemoryStore.load(path))["learning_id"] == ["l1"]
    assert "l1" not in kb_path.read_text(encoding="utf-8")

    assert store.save() == []
    assert store.pending_count() == 0
    assert _keys(MemoryStore.load(path))["learning_id"] == ["l1"]
    assert "l1" in kb_path.read_text(encoding="utf-8")


# --- DOD-08 / REQ-05: one quarantine winner -------------------------------


def _loader(path: str, barrier) -> None:
    barrier.wait(timeout=30)
    store = MemoryStore.load(path)
    # Every loader recovers to an empty state instead of raising.
    assert store.records()["runs"] == []


def test_concurrent_loaders_create_one_corrupt_backup(tmp_path: Path) -> None:
    """DOD-08: concurrent loaders of one corrupt document make one backup."""

    ctx = _context()
    path = tmp_path / MEMORY_DOC_NAME
    payload = json.dumps(CORRUPT_DOC).encode("utf-8")
    path.write_bytes(payload)

    loaders = 4
    barrier = ctx.Barrier(loaders)
    processes = [
        ctx.Process(target=_loader, args=(str(path), barrier))
        for _ in range(loaders)
    ]
    for process in processes:
        process.start()
    for process in processes:
        process.join(timeout=120)
        assert process.exitcode == 0

    backups = sorted(tmp_path.glob(MEMORY_DOC_NAME + ".corrupt-*"))
    assert len(backups) == 1, [backup.name for backup in backups]
    assert backups[0].read_bytes() == payload

    # A record after the stampede survives a reload.
    store = MemoryStore.load(path)
    store.record_run({"run_id": "after", "task_id": "a", "state": "COMPLETED"})
    assert store.save(known_task_ids={"a"}) == []
    assert _keys(MemoryStore.load(path))["run_id"] == ["after"]


# --- DOD-09 / REQ-06: read paths see a peer's write ----------------------


def test_read_paths_refresh_after_peer_write(tmp_path: Path) -> None:
    """DOD-09: the same instance observes records another process wrote."""

    path = tmp_path / MEMORY_DOC_NAME
    reader = MemoryStore.load(path)
    # The empty-document case still answers without inventing content.
    assert reader.get_context_for_prompt() == ""
    assert reader.recent_task_names() == []

    writer = MemoryStore(path, clock=lambda: 20.0)
    writer.record_run({"run_id": "r-peer", "task_id": "peer-task",
                       "state": "COMPLETED"})
    writer.record_learning({"learning_id": "l-peer", "text": "peer lesson"})
    assert writer.save(known_task_ids={"peer-task"}) == []

    context = reader.get_context_for_prompt()
    assert "r-peer" in context
    assert "l-peer" in context
    assert reader.recent_task_names() == ["peer-task"]

    # Nothing changed since that sync: the cheap path answers and says so.
    assert reader.refresh_from_disk() is False

    # A record this process appended but has not saved is kept by the refresh
    # triggered by the peer's next write.
    reader.record_run({"run_id": "r-mine", "task_id": "my-task",
                       "state": "COMPLETED"})
    writer.record_learning({"learning_id": "l-peer-2", "text": "second lesson"})
    assert writer.save() == []
    assert reader.refresh_from_disk() is True
    keys = _keys(reader)
    assert sorted(keys["run_id"]) == ["r-mine", "r-peer"]
    assert keys["learning_id"] == ["l-peer", "l-peer-2"]


# --- DOD-11 / REQ-03, REQ-07: rollback and prune scrub pending ------------


def test_restore_records_recomputes_pending(tmp_path: Path) -> None:
    """DOD-11: a rolled-back cycle never re-persists a discarded record."""

    path = tmp_path / MEMORY_DOC_NAME
    peer = MemoryStore(path, clock=lambda: 30.0)
    peer.record_run({"run_id": "r-peer", "task_id": "a", "state": "COMPLETED"})
    assert peer.save(known_task_ids={"a"}) == []

    store = MemoryStore.load(path)
    snapshot = store.snapshot_records()
    store.record_run({"run_id": "r-discarded", "task_id": "b",
                      "state": "PROGRESS"}, cycle=7)
    store.record_learning({"learning_id": "l-discarded", "text": "gone"},
                          cycle=7)
    assert store.pending_count() == 2

    store.restore_records(snapshot)
    assert store.pending_count() == 0
    assert store.save(known_task_ids={"a", "b"}) == []
    records = MemoryStore.load(path).records()
    assert [record["run_id"] for record in records["runs"]] == ["r-peer"]
    assert records["learnings"] == []

    # ``drop_records`` and ``prune`` forget pending ids the same way.
    store.record_run({"run_id": "r-dropped", "task_id": "c",
                      "state": "PROGRESS"}, cycle=9)
    assert store.drop_records(cycle=9) == 1
    assert store.pending_count() == 0

    store.record_learning({"learning_id": "l-old", "text": "old"})
    store.record_learning({"learning_id": "l-new", "text": "new"})
    assert store.prune(max_learnings=1) == {"runs": 0, "bug_fixes": 0,
                                           "learnings": 1}
    assert store.pending_count() == 1
    assert store.save() == []
    assert _keys(MemoryStore.load(path))["learning_id"] == ["l-new"]
