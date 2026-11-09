"""Journal reconciliation: torn ``PENDING`` pairs are terminalised at startup.

``src/ai_maintainer/audit.py`` documents that a crash between a repair's
``PENDING`` line and its terminal ``SUCCEEDED``/``FAILED`` line leaves an orphan
``PENDING`` forever. This module covers the pass that resolves those orphans
(REQ-01 .. REQ-05, DOD-01 .. DOD-09):

* confirmed — the store already holds the expected repair token,
* not applied — the store still holds the pre-image, or the signal is gone,
* diverged / malformed / unsupported — terminalised as ``REFUSED``,
* already terminal — untouched, and a second pass appends nothing,
* per-entry failure — isolated, other orphans still reconciled,
* ordering — reconciliation runs before ``recover_all`` writes new ``PENDING``s.

Every test builds its own store layout under ``tmp_path`` and drives the service
with an injected clock, so nothing here touches the real
``/home/aiteam/ai_team/data`` tree and no test sleeps. Torn pairs are produced
with the *real* writer (``AuditedOperations.new_entry`` + ``record``), so the
pre-image under test is exactly the one a crash would leave behind.
"""

from __future__ import annotations

import dataclasses
import json
from pathlib import Path

import pytest

from ai_maintainer.audit import (
    PHASE_FAILED,
    PHASE_PENDING,
    PHASE_REFUSED,
    PHASE_SUCCEEDED,
    AuditedOperations,
    OperationType,
    entries_by_id,
    read_journal,
)
from ai_maintainer.consistency import (
    JOURNAL_RECONCILIATION,
    PROGRESS_FILES,
    RECONCILIATION_WRITE_CONFIRMED,
    RECONCILIATION_WRITE_NOT_APPLIED,
    SIGNAL_FILE,
    ConsistencyService,
    StoreRoots,
    read_snapshot,
    run_startup_recovery,
)

TASK = "UTILTOOLS-901"
OTHER = "UTILTOOLS-902"
PROGRESS_FILE = PROGRESS_FILES[0]
COUNTER_KEYS = ("scanned_pending", "confirmed", "not_applied", "refused", "failed")


class FrozenClock:
    """Clock that only moves when a test moves it."""

    def __init__(self, now: float = 1000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now


# --------------------------------------------------------------------------- #
# Layout helpers (mirrors tests/test_consistency_recovery.py)
# --------------------------------------------------------------------------- #


@pytest.fixture()
def roots(tmp_path: Path) -> StoreRoots:
    state_root = tmp_path / "active_task"
    state_root.mkdir()
    return StoreRoots.for_state_root(state_root)


@pytest.fixture()
def clock() -> FrozenClock:
    return FrozenClock()


@pytest.fixture()
def service(roots: StoreRoots, clock: FrozenClock) -> ConsistencyService:
    return ConsistencyService(roots, clock=clock)


def task_dir(roots: StoreRoots, task: str = TASK) -> Path:
    assert roots.state_root is not None
    path = roots.state_root / task
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_signal(roots: StoreRoots, token: str, task: str = TASK) -> Path:
    path = task_dir(roots, task) / SIGNAL_FILE
    path.write_text(token + "\n", encoding="utf-8")
    return path


def write_progress(roots: StoreRoots, line: str, task: str = TASK) -> Path:
    path = task_dir(roots, task) / PROGRESS_FILE
    path.write_text(f"step=started\n{line}\n", encoding="utf-8")
    return path


def append_raw_line(roots: StoreRoots, payload: dict) -> None:
    """Hand-edit the journal the way a foreign writer or restore could."""

    path = roots.journal_path
    assert path is not None
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload) + "\n")


def journal(roots: StoreRoots, service: ConsistencyService | None = None) -> list[dict]:
    path = roots.journal_path
    assert path is not None
    if not path.is_file():
        return []
    return read_journal(path)


def drift(roots: StoreRoots, task: str = TASK) -> None:
    """Terminal progress vs an active signal: canonical ``COMPLETED`` / ``Done``."""

    write_progress(roots, "Done 5", task)
    write_signal(roots, "Running 1", task)


def torn_pending(
    service: ConsistencyService,
    task: str = TASK,
    *,
    mutate: callable | None = None,
    snapshot_task: str | None = None,
) -> str:
    """Write the ``PENDING`` half of a repair pair, then "crash" (no terminal).

    The pre-image comes from the real snapshot of ``snapshot_task`` (default
    ``task``), so it is byte-identical to what ``_recover_one`` would have
    journaled before its signal write.
    """

    target = snapshot_task or task
    image = read_snapshot(target, service._roots).before_image
    if mutate is not None:
        mutate(image)
    entry = service._audit.new_entry(
        OperationType.CONSISTENCY_RECOVERY,
        task=task,
        store=SIGNAL_FILE,
        action="ALIGN_SIGNAL_TO_PROGRESS",
        before=image,
    )
    service._audit.record(entry)
    return entry.entry_id


def group_of(roots: StoreRoots, entry_id: str) -> list[dict]:
    return entries_by_id(journal(roots))[entry_id]


def phases(roots: StoreRoots) -> list[str]:
    return [line["phase"] for line in journal(roots)]


def tree_bytes(root: Path) -> dict[str, bytes]:
    return {
        str(path.relative_to(root)): path.read_bytes()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


# --------------------------------------------------------------------------- #
# DOD-01: orphan whose store already holds the canonical token is confirmed
# --------------------------------------------------------------------------- #


def test_orphan_pending_with_canonical_head_is_confirmed(roots, service):
    drift(roots)
    entry_id = torn_pending(service)
    # The crash landed *after* the signal write: the store already says "Done".
    write_signal(roots, "Done")
    before = (task_dir(roots) / SIGNAL_FILE).read_bytes()

    counters = service.reconcile_journal()

    assert counters["scanned_pending"] == 1
    assert counters["confirmed"] == 1
    assert counters["reconciled"] == 1
    assert counters["not_applied"] == 0
    group = group_of(roots, entry_id)
    assert [line["phase"] for line in group] == [PHASE_PENDING, PHASE_SUCCEEDED]
    assert group[-1]["after"][JOURNAL_RECONCILIATION] == RECONCILIATION_WRITE_CONFIRMED
    assert group[-1]["entry_id"] == group[0]["entry_id"]
    # INV-JR-2: the pass appends to the journal only; the store is untouched.
    assert (task_dir(roots) / SIGNAL_FILE).read_bytes() == before


# --------------------------------------------------------------------------- #
# DOD-02: pre-image still live means the write never landed
# --------------------------------------------------------------------------- #


def test_orphan_pending_pre_image_intact_is_not_applied(roots, service):
    drift(roots)
    entry_id = torn_pending(service)
    before = (task_dir(roots) / SIGNAL_FILE).read_bytes()

    counters = service.reconcile_journal()

    assert counters["not_applied"] == 1
    assert counters["confirmed"] == 0
    group = group_of(roots, entry_id)
    assert [line["phase"] for line in group] == [PHASE_PENDING, PHASE_FAILED]
    assert group[-1]["after"][JOURNAL_RECONCILIATION] == RECONCILIATION_WRITE_NOT_APPLIED
    assert group[-1]["after"]["signal_head"] == "Running 1"
    assert group[-1]["after"]["expected_token"] == "Done"
    assert (task_dir(roots) / SIGNAL_FILE).read_bytes() == before

    # The terminalised orphan does not block the next startup: recover_all()
    # performs a *new* repair instead of reusing the torn entry (REQ-04).
    summary = service.recover_all()

    assert summary["recovered"] == 1
    assert (task_dir(roots) / SIGNAL_FILE).read_text(encoding="utf-8").strip() == "Done"
    groups = entries_by_id(journal(roots))
    # The torn entry exists exactly once and is terminal; the new repair is a
    # separate pair (INV-JR-3: reconciliation never appended a second PENDING).
    assert [line["phase"] for line in groups[entry_id]] == [PHASE_PENDING, PHASE_FAILED]
    assert len(groups) == 2
    assert [line["phase"] for line in list(groups.values())[-1]] == [
        PHASE_PENDING,
        PHASE_SUCCEEDED,
    ]


def test_orphan_pending_with_absent_signal_is_not_applied(roots, service):
    drift(roots)
    entry_id = torn_pending(service)
    (task_dir(roots) / SIGNAL_FILE).unlink()

    counters = service.reconcile_journal()

    assert counters["not_applied"] == 1
    terminal = group_of(roots, entry_id)[-1]
    assert terminal["phase"] == PHASE_FAILED
    assert terminal["after"]["reason"] == "signal_absent"
    assert terminal["after"]["signal_present"] is False
    # Absence is evidence, not an instruction to (re)create the file.
    assert not (task_dir(roots) / SIGNAL_FILE).exists()


# --------------------------------------------------------------------------- #
# DOD-04: third value, malformed pre-image, unsafe task -> REFUSED
# --------------------------------------------------------------------------- #


def test_diverged_and_malformed_orphans_are_refused(roots, service):
    drift(roots)
    entry_id = torn_pending(service)
    write_signal(roots, "In Progress 3")
    before = (task_dir(roots) / SIGNAL_FILE).read_bytes()

    counters = service.reconcile_journal()

    assert counters["refused"] == 1
    assert counters["not_applied"] == 0
    terminal = group_of(roots, entry_id)[-1]
    assert terminal["phase"] == PHASE_REFUSED
    assert terminal["after"]["reason"] == "head_diverged"
    assert terminal["after"]["signal_head"] == "In Progress 3"
    assert (task_dir(roots) / SIGNAL_FILE).read_bytes() == before


def test_second_malformed_orphan_is_also_refused(roots, service):
    drift(roots)
    missing_canonical = torn_pending(service, mutate=lambda image: image.pop("canonical"))
    no_before = torn_pending(service, mutate=lambda image: image.clear())
    unwritable = torn_pending(
        service, mutate=lambda image: image.update(canonical="RUNNING")
    )

    counters = service.reconcile_journal()

    assert counters["refused"] == 3
    assert counters["failed"] == 0
    assert group_of(roots, missing_canonical)[-1]["after"]["reason"] == "malformed_before"
    assert group_of(roots, no_before)[-1]["after"]["reason"] == "malformed_before"
    assert group_of(roots, unwritable)[-1]["after"]["reason"] == "no_writable_token"
    for entry_id in (missing_canonical, no_before, unwritable):
        assert [line["phase"] for line in group_of(roots, entry_id)] == [
            PHASE_PENDING,
            PHASE_REFUSED,
        ]


def test_untrusted_journal_lines_are_refused_not_followed(roots, service):
    drift(roots)
    foreign_op = torn_pending(service, mutate=lambda image: image.update())
    # A well-formed pre-image under an unsafe task id: only the path guard saves us.
    append_raw_line(
        roots,
        {
            "entry_id": "traversal",
            "op": OperationType.CONSISTENCY_RECOVERY.value,
            "task": "../../etc",
            "store": SIGNAL_FILE,
            "action": "ALIGN_SIGNAL_TO_PROGRESS",
            "phase": PHASE_PENDING,
            "before": {"canonical": "COMPLETED", "signal_head": "Running 1"},
            "after": {},
            "recorded_at": 0.0,
        },
    )
    append_raw_line(
        roots,
        {
            "entry_id": "deadbeef",
            "op": "SOMETHING_ELSE",
            "task": TASK,
            "store": SIGNAL_FILE,
            "action": "x",
            "phase": PHASE_PENDING,
            "before": {"canonical": "COMPLETED", "signal_head": "Running 1"},
            "after": {},
            "recorded_at": 0.0,
        },
    )

    counters = service.reconcile_journal()

    assert counters["refused"] == 2
    assert group_of(roots, "traversal")[-1]["after"]["reason"] == "invalid_task"
    # A foreign op is terminalised too: leaving it PENDING would reproduce the bug.
    assert group_of(roots, "deadbeef")[-1]["after"]["reason"] == "unsupported_op"
    # The well-formed pre-image orphans resolve normally, side by side.
    assert counters["not_applied"] == 1
    assert [line["phase"] for line in group_of(roots, foreign_op)] == [
        PHASE_PENDING,
        PHASE_FAILED,
    ]
    assert (task_dir(roots) / SIGNAL_FILE).read_text(encoding="utf-8").strip() == "Running 1"


def test_symlinked_signal_is_refused_not_called_absent(roots, service, tmp_path):
    """An unsafe slot yields no evidence, so the pass refuses instead of denying.

    Plan §4a/§4b row 6: folding "I could not read it" into "it is gone" would
    append a positive ``WRITE_NOT_APPLIED`` claim about a store never observed.
    """

    drift(roots)
    entry_id = torn_pending(service)
    outside = tmp_path / "outside_signal.txt"
    outside.write_text("Running 1\n", encoding="utf-8")
    signal = task_dir(roots) / SIGNAL_FILE
    signal.unlink()
    signal.symlink_to(outside)

    counters = service.reconcile_journal()

    assert counters["refused"] == 1
    assert counters["not_applied"] == 0
    assert counters["confirmed"] == 0
    terminal = group_of(roots, entry_id)[-1]
    assert terminal["phase"] == PHASE_REFUSED
    assert terminal["after"]["reason"] == "unreadable_store"
    # Never followed: the link target is untouched and still linked.
    assert signal.is_symlink()
    assert outside.read_text(encoding="utf-8") == "Running 1\n"


def test_unreadable_signal_is_refused_not_called_absent(roots, service, monkeypatch):
    """An ``OSError`` on the signal read is refusals, not a ``WRITE_NOT_APPLIED``."""

    drift(roots)
    entry_id = torn_pending(service)
    real_open = Path.open
    signal = task_dir(roots) / SIGNAL_FILE

    def deny(self, *args, **kwargs):
        if self == signal:
            raise PermissionError("denied")
        return real_open(self, *args, **kwargs)

    monkeypatch.setattr(Path, "open", deny)

    counters = service.reconcile_journal()

    assert counters["refused"] == 1
    assert counters["not_applied"] == 0
    terminal = group_of(roots, entry_id)[-1]
    assert terminal["phase"] == PHASE_REFUSED
    assert terminal["after"]["reason"] == "unreadable_store"
    monkeypatch.undo()
    assert signal.read_text(encoding="utf-8").strip() == "Running 1"


# --------------------------------------------------------------------------- #
# DOD-05: one failing append is counted and isolated
# --------------------------------------------------------------------------- #


class FlakyAudit(AuditedOperations):
    """Journal writer that refuses to append terminal lines for one entry id."""

    def __init__(self, journal_path, clock, *, bad_entry_id: str) -> None:
        super().__init__(journal_path, clock=clock)
        self.bad_entry_id = bad_entry_id

    def append_terminal(self, line, phase, after=None, *, at=None):  # noqa: D102
        if line.get("entry_id") == self.bad_entry_id:
            raise OSError("journal is read-only")
        return super().append_terminal(line, phase, after, at=at)


def test_per_entry_failure_is_isolated_and_counted(roots, clock):
    drift(roots)
    good = torn_pending(service=ConsistencyService(roots, clock=clock))
    write_signal(roots, "Done")
    second = torn_pending(
        service=ConsistencyService(roots, clock=clock), task=OTHER, snapshot_task=TASK
    )
    flaky = FlakyAudit(roots.journal_path, clock, bad_entry_id=second)

    summary = run_startup_recovery(roots, audit=flaky, clock=clock)

    assert isinstance(summary["journal_reconciliation"], dict)
    counters = summary["journal_reconciliation"]
    assert counters["failed"] == 1
    assert counters["confirmed"] == 1
    assert counters["scanned_pending"] == 2
    # The isolated entry is still PENDING (retryable), the other is terminal.
    assert [line["phase"] for line in group_of(roots, good)] == [
        PHASE_PENDING,
        PHASE_SUCCEEDED,
    ]
    assert [line["phase"] for line in group_of(roots, second)] == [PHASE_PENDING]
    # The isolated entry is retryable, not lost: a healthy writer resolves it.
    retry = ConsistencyService(roots, clock=clock).reconcile_journal()
    assert retry["failed"] == 0
    assert retry["scanned_pending"] == 1
    assert [line["phase"] for line in group_of(roots, second)] == [PHASE_PENDING, PHASE_FAILED]


# --------------------------------------------------------------------------- #
# DOD-06: idempotence — terminal groups and repeat passes append nothing
# --------------------------------------------------------------------------- #


def test_second_pass_and_terminal_groups_are_noops(roots, service):
    drift(roots)
    torn_pending(service)
    write_signal(roots, "Done")

    first = service.reconcile_journal()
    lines_after_first = journal(roots)
    second = service.reconcile_journal()

    assert first["reconciled"] == 1
    assert second["scanned_pending"] == 0
    assert second["reconciled"] == 0
    for key in ("confirmed", "not_applied", "refused", "failed"):
        assert second[key] == 0
    assert journal(roots) == lines_after_first


def test_terminal_groups_stay_untouched(roots, service):
    drift(roots)
    summary = service.recover_all()
    assert summary["recovered"] == 1
    lines = journal(roots)
    assert [line["phase"] for line in lines] == [PHASE_PENDING, PHASE_SUCCEEDED]

    counters = service.reconcile_journal()

    assert counters["scanned_pending"] == 0
    assert counters["reconciled"] == 0
    assert journal(roots) == lines


def test_journal_path_none_is_a_noop(roots, clock):
    drift(roots)
    torn_pending(ConsistencyService(roots, clock=clock))
    headless = dataclasses.replace(roots, journal_path=None)
    service = ConsistencyService(headless, clock=clock)

    counters = service.reconcile_journal()
    summary = service.recover_all()

    assert counters["journal"] is None
    assert counters["scanned_pending"] == 0
    assert counters["reconciled"] == 0
    assert summary["journal_reconciliation"]["journal"] is None
    # The real journal keeps its single torn PENDING line: nothing was written.
    assert [line["phase"] for line in journal(roots)] == [PHASE_PENDING]


def test_missing_journal_file_is_empty_not_an_error(roots, service):
    counters = service.reconcile_journal()

    assert counters["scanned_pending"] == 0
    assert counters["reconciled"] == 0
    assert journal(roots) == []


# --------------------------------------------------------------------------- #
# DOD-03: the pass writes only journal lines
# --------------------------------------------------------------------------- #


def test_reconciliation_never_writes_a_store(roots, service, tmp_path):
    drift(roots)
    archive = dict(roots.archive_roots)["hist_tasks"] / TASK
    archive.mkdir(parents=True)
    (archive / "result.md").write_text("done\n", encoding="utf-8")
    timeline = roots.timeline_path
    assert timeline is not None
    timeline.write_text(json.dumps({"task": TASK, "state": "COMPLETED"}) + "\n", encoding="utf-8")
    torn_pending(service)
    write_signal(roots, "Done")
    journal_key = str(roots.journal_path.relative_to(tmp_path))
    before = tree_bytes(tmp_path)

    counters = service.reconcile_journal()

    after = tree_bytes(tmp_path)
    assert counters["confirmed"] == 1
    # Only the journal grew: no store, archive or timeline byte moved.
    assert {key: after[key] for key in before if key != journal_key} == {
        key: value for key, value in before.items() if key != journal_key
    }
    assert set(after) == set(before)
    assert after[journal_key] != before[journal_key]
    assert after[journal_key].startswith(before[journal_key])


# --------------------------------------------------------------------------- #
# DOD-08: counters ride along in the recovery summary
# --------------------------------------------------------------------------- #


def test_summary_carries_bounded_counters(roots, service):
    drift(roots)
    torn_pending(service)
    write_signal(roots, "Done")

    summary = service.recover_all()

    counters = summary["journal_reconciliation"]
    assert isinstance(counters, dict)
    for key in COUNTER_KEYS:
        assert isinstance(counters[key], int)
    assert counters["confirmed"] == 1
    assert counters["reconciled"] == 1
    assert isinstance(counters["entries"], list)
    assert counters["entries"][0]["entry_id"]
    # REQ-03: the existing top-level counters keep their established meaning.
    assert (summary["inconsistent"], summary["recovered"], summary["failed"]) == (0, 0, 0)


# --------------------------------------------------------------------------- #
# DOD-09: reconciliation runs *before* the scan writes new PENDING lines
# --------------------------------------------------------------------------- #


def test_reconciliation_runs_before_new_pending_lines(roots, service):
    drift(roots)
    orphan = torn_pending(service)
    write_signal(roots, "Done")
    drift(roots, OTHER)

    summary = service.recover_all()

    assert summary["journal_reconciliation"]["confirmed"] == 1
    assert summary["recovered"] == 1
    lines = journal(roots)
    # Orphan: exactly one PENDING (never a second pair) and it is now terminal.
    orphan_lines = [line for line in lines if line["entry_id"] == orphan]
    assert [line["phase"] for line in orphan_lines] == [PHASE_PENDING, PHASE_SUCCEEDED]
    orphan_terminal_at = lines.index(orphan_lines[-1])
    new_pending = [
        index
        for index, line in enumerate(lines)
        if line["phase"] == PHASE_PENDING and line["entry_id"] != orphan
    ]
    assert len(new_pending) == 1
    assert orphan_terminal_at < new_pending[0]
    # The new repair's own pair is complete, so a later pass has nothing to do.
    assert service.reconcile_journal()["scanned_pending"] == 0
