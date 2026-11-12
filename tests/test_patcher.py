"""Durable, process-safe autonomous commit budget (UTILTOOLS-126).

Covers task-brief REQ-01 .. REQ-05 with one test per Definition-of-Done row:

* ``test_budget_survives_restart`` - DOD-01: a second ``PatchManager`` over the
  same ``data_dir`` sees the cap already used and the ledger on disk (REQ-01,
  REQ-02).
* ``test_last_slot_race_single_winner`` - DOD-02: two managers race for the last
  slot under the *real* ``flock``; exactly one reservation wins (REQ-02).
* ``test_reservation_release_and_lease_expiry`` - DOD-03: every non-committing
  outcome releases the slot and a crashed reservation expires with its lease
  (REQ-03).
* ``test_trailer_reconciliation_counts_unrecorded_commit`` - DOD-04: a commit
  that reached git while its ledger write was skipped is counted by the
  ``Maintainer-Commit`` trailer (REQ-04).
* ``test_corrupt_ledger_quarantined_fail_closed`` - DOD-05: a corrupt ledger is
  quarantined, rebuilt from git, and the cap is still enforced; the
  ``max_commits_per_hour <= 0`` bypass survives (REQ-05).
* ``test_max_commits_per_hour_config`` - DOD-06: the new knob flows through the
  five config layers with a ``0`` default and a legal bypass (REQ-05).

Every test points ``data_dir`` at ``tmp_path`` (never the repo's ``data/``) and
drives the lease/window edges with an injected clock, so nothing sleeps and
nothing touches the real maintainer tree. The git history used by the
reconciliation tests is a throwaway repo under ``tmp_path``.
"""

from __future__ import annotations

import json
import os
import subprocess
import threading
from pathlib import Path

import pytest

from ai_maintainer.config import MaintainerConfig
from ai_maintainer.patcher import PatchManager
from ai_maintainer.patcher.patch_manager import (
    LEDGER_CORRUPT,
    LEDGER_NAME,
    LEDGER_WINDOW_SEC,
    MAINTAINER_COMMIT_TRAILER,
    RELEASE_COMMIT_FAILED,
    RELEASE_NON_COMMITTING_REVIEW,
    RELEASE_POLICY_REFUSED,
    RELEASE_TEST_VERIFICATION,
    RESERVATION_LEASE_SEC,
    STATUS_CANCELLED,
    STATUS_COMMITTED,
    STATUS_RESERVED,
    CommitBudgetExhausted,
    CommitLedger,
    CommitPolicyRefused,
    CommitVerificationFailed,
    commit_message_with_trailer,
    ledger_path,
    resolve_data_dir,
)

TASK = "UTILTOOLS-126"
OTHER_TASK = "UTILTOOLS-127"


class Clock:
    """Clock that only moves when a test moves it (no ``sleep`` anywhere)."""

    def __init__(self, now: float = 1_765_000_000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def make_config(data_dir: Path, limit: int, **overrides) -> MaintainerConfig:
    """A config whose only interesting knobs are the ledger dir and the cap."""

    return MaintainerConfig(
        data_dir=str(data_dir),
        max_commits_per_hour=limit,
        **overrides,
    )


def read_ledger(data_dir: Path) -> dict:
    """The raw ledger document (asserts the contract shape on disk)."""

    return json.loads((data_dir / LEDGER_NAME).read_text(encoding="utf-8"))


def entries_by_status(data_dir: Path, status: str) -> list[dict]:
    return [e for e in read_ledger(data_dir)["entries"] if e["status"] == status]


def git(repo: Path, *args: str, env: dict | None = None) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
        check=True,
        env=env,
    )
    return completed.stdout.strip()


def init_repo(root: Path) -> Path:
    """Throwaway git repo with a pinned identity (and a pinned 'now')."""

    repo = root / "repo"
    repo.mkdir(parents=True, exist_ok=True)
    git(repo, "init", "-b", "main")
    git(repo, "config", "user.name", "AI Team Test")
    git(repo, "config", "user.email", "ai-team-test@local")
    return repo


def commit_file(repo: Path, name: str, message: str, when: str) -> str:
    """Commit ``name`` with a fixed author/committer date; return the sha."""

    target = repo / name
    target.write_text(name, encoding="utf-8")
    git(repo, "add", name)
    env = dict(os.environ)
    env.update(
        GIT_AUTHOR_DATE=when,
        GIT_COMMITTER_DATE=when,
        GIT_AUTHOR_NAME="AI Team Test",
        GIT_AUTHOR_EMAIL="ai-team-test@local",
        GIT_COMMITTER_NAME="AI Team Test",
        GIT_COMMITTER_EMAIL="ai-team-test@local",
    )
    git(repo, "commit", "-m", message, env=env)
    return git(repo, "rev-parse", "HEAD")


def window_stamp(clock: Clock) -> str:
    """A git date string for the clock's current instant."""

    return f"@{int(clock.now)} +0000"


# ---------------------------------------------------------------------------
# DOD-01 / REQ-01 + REQ-02: the budget survives a restart
# ---------------------------------------------------------------------------


def test_budget_survives_restart(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    config = make_config(data_dir, limit=2)
    clock = Clock()

    first = PatchManager(config, clock=clock)
    assert first.max_commits_per_hour == 2
    # REQ-01: the ledger lives under config.data_dir; nothing is in memory.
    assert ledger_path(config) == data_dir / LEDGER_NAME
    assert resolve_data_dir(config) == data_dir
    assert not hasattr(first, "_commit_times")

    token_a = first.reserve(TASK)
    token_b = first.reserve(OTHER_TASK)
    assert token_a and token_b and token_a != token_b
    assert first.reserve(TASK) is None, "the third slot exceeds the cap of 2"

    # Only a *successful* commit consumes a slot.
    assert first.consume(token_a, "a" * 40) is True
    assert first.consume(token_b, "b" * 40) is True

    # Restart: a brand-new manager (fresh process in production) over the same
    # data_dir must still see two slots used and refuse the next commit.
    second = PatchManager(config, clock=clock)
    assert second.used() == 2
    assert second.reserve(TASK) is None, "a restart must not reset the budget"

    # REQ-01: the count and the rolling window are visible on disk.
    document = read_ledger(data_dir)
    assert len(document["entries"]) == 2
    assert {entry["status"] for entry in document["entries"]} == {STATUS_COMMITTED}
    assert {entry["commit_sha"] for entry in document["entries"]} == {"a" * 40, "b" * 40}
    view = second.snapshot()
    assert view["used"] == 2
    assert view["window_sec"] == LEDGER_WINDOW_SEC == 3600.0
    assert view["bypass"] is False and view["max_commits_per_hour"] == 2
    assert view["path"] == str(data_dir / LEDGER_NAME)

    # The window is rolling: past 3600s the receipts age out and slots free up.
    clock.advance(LEDGER_WINDOW_SEC + 1.0)
    third = PatchManager(config, clock=clock)
    assert third.used() == 0
    assert third.reserve(TASK) is not None


# ---------------------------------------------------------------------------
# DOD-02 / REQ-02: exactly one winner for the last slot
# ---------------------------------------------------------------------------


def test_last_slot_race_single_winner(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    config = make_config(data_dir, limit=2)
    clock = Clock()

    # One slot already committed, so the racers compete for the final one.
    warmup = PatchManager(config, clock=clock)
    warm_token = warmup.reserve(TASK)
    assert warm_token is not None
    assert warmup.consume(warm_token, "c" * 40) is True
    assert warmup.used() == 1

    barrier = threading.Barrier(2)
    results: dict[str, str | None] = {}

    def race(name: str) -> None:
        # A separate PatchManager (and ledger object) per thread: nothing is
        # shared but the file and the real flock guarding it.
        manager = PatchManager(config, clock=clock)
        barrier.wait(timeout=10)
        results[name] = manager.reserve(TASK)

    threads = [
        threading.Thread(target=race, args=("left",), daemon=True),
        threading.Thread(target=race, args=("right",), daemon=True),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)
        assert not thread.is_alive()

    tokens = [token for token in results.values() if token is not None]
    assert len(tokens) == 1, f"exactly one winner expected, got {results}"
    assert len(results) == 2 and None in results.values()

    # The winner's reservation is live and the loser left no trace behind it.
    live = entries_by_status(data_dir, STATUS_RESERVED)
    assert len(live) == 1
    assert live[0]["token"] == tokens[0]
    assert live[0]["task_id"] == TASK
    assert PatchManager(config, clock=clock).used() == 2


# ---------------------------------------------------------------------------
# DOD-03 / REQ-03: release on every non-committing path + lease expiry
# ---------------------------------------------------------------------------


def test_reservation_release_and_lease_expiry(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    config = make_config(data_dir, limit=1)
    clock = Clock()
    manager = PatchManager(config, clock=clock)

    # A live reservation already fills the single slot.
    token = manager.reserve(TASK)
    assert token is not None
    assert manager.reserve(TASK) is None

    # release() frees it immediately (abort / policy / failed verification).
    assert manager.release(token, "unit-test") is True
    assert manager.release(token, "unit-test") is False, "release is idempotent"
    assert manager.used() == 0
    assert entries_by_status(data_dir, STATUS_CANCELLED)[0]["reason"] == "unit-test"
    spare = manager.reserve(TASK)
    assert spare is not None
    assert manager.release(spare, "unit-test") is True
    assert manager.used() == 0

    # The guarded commit block releases on every failing outcome (REQ-03).
    outcomes: list[tuple[str, str]] = []

    def refuse_policy(_message: str) -> bool:
        return False

    with pytest.raises(CommitPolicyRefused):
        manager.commit(
            task_id=TASK,
            summary="refused by policy",
            commit_fn=lambda message: "d" * 40,
            policy=refuse_policy,
        )
    outcomes.append(("policy", entries_by_status(data_dir, STATUS_CANCELLED)[-1]["reason"]))
    assert manager.snapshot()["used"] == 0, "policy refusal must not burn a slot"

    def explode(_message: str) -> str:
        raise RuntimeError("commit subprocess failed")

    with pytest.raises(RuntimeError):
        manager.commit(task_id=TASK, summary="commit fails", commit_fn=explode)
    outcomes.append(("failure", entries_by_status(data_dir, STATUS_CANCELLED)[-1]["reason"]))
    assert manager.snapshot()["used"] == 0

    with pytest.raises(KeyboardInterrupt):
        manager.commit(
            task_id=TASK,
            summary="aborted",
            commit_fn=lambda message: (_ for _ in ()).throw(KeyboardInterrupt()),
        )
    outcomes.append(("abort", entries_by_status(data_dir, STATUS_CANCELLED)[-1]["reason"]))
    assert manager.snapshot()["used"] == 0

    with pytest.raises(CommitVerificationFailed):
        manager.commit(
            task_id=TASK,
            summary="tests fail",
            commit_fn=lambda message: "e" * 40,
            verify_fn=lambda sha: False,
        )
    outcomes.append(("verify", entries_by_status(data_dir, STATUS_CANCELLED)[-1]["reason"]))
    assert manager.snapshot()["used"] == 0

    assert (
        manager.commit(
            task_id=TASK,
            summary="review only",
            commit_fn=lambda message: None,
        )
        is None
    )
    outcomes.append(
        ("review", entries_by_status(data_dir, STATUS_CANCELLED)[-1]["reason"])
    )
    assert manager.snapshot()["used"] == 0

    assert [reason for _, reason in outcomes] == [
        RELEASE_POLICY_REFUSED,
        RELEASE_COMMIT_FAILED,
        "aborted",
        RELEASE_TEST_VERIFICATION,
        RELEASE_NON_COMMITTING_REVIEW,
    ]

    # The success path consumes the slot with the sha it committed.
    sha = manager.commit(
        task_id=TASK,
        summary="committed",
        body="body line",
        commit_fn=lambda message: "f" * 40,
        verify_fn=lambda sha: True,
    )
    assert sha == "f" * 40
    committed = entries_by_status(data_dir, STATUS_COMMITTED)
    assert len(committed) == 1 and committed[0]["commit_sha"] == "f" * 40
    assert manager.reserve(TASK) is None

    # Crash path: a reservation whose owner never released it expires with its
    # lease, so a dead process cannot wedge the budget.
    # The committed slot ages out of the rolling window (strictly past 3600s).
    crash_clock = Clock(clock.now + LEDGER_WINDOW_SEC + 1.0)
    crashed = PatchManager(config, clock=crash_clock)
    crash_token = crashed.reserve(TASK)
    assert crash_token is not None
    assert PatchManager(config, clock=crash_clock).reserve(TASK) is None

    crash_clock.advance(RESERVATION_LEASE_SEC - 1.0)
    assert (
        PatchManager(config, clock=crash_clock).reserve(TASK) is None
    ), "a reservation is still live inside its lease"

    crash_clock.advance(2.0)  # now past the 300s lease
    survivor = PatchManager(config, clock=crash_clock)
    assert survivor.reserve(TASK) is not None, "an expired lease must free the slot"
    assert entries_by_status(data_dir, STATUS_RESERVED) == [] or all(
        entry["token"] != crash_token
        for entry in entries_by_status(data_dir, STATUS_RESERVED)
    )


# ---------------------------------------------------------------------------
# DOD-04 / REQ-04: trailer reconciliation catches a skipped ledger write
# ---------------------------------------------------------------------------


def test_trailer_reconciliation_counts_unrecorded_commit(tmp_path: Path) -> None:
    clock = Clock()
    repo = init_repo(tmp_path)
    data_dir = tmp_path / "data"
    config = make_config(data_dir, limit=2)

    marked_sha = commit_file(
        repo,
        "marked.txt",
        f"feat: periodic diagnosis\n\n{MAINTAINER_COMMIT_TRAILER}: {TASK}",
        window_stamp(clock),
    )
    unmarked_sha = commit_file(
        repo, "human.txt", "chore: a human commit", window_stamp(clock)
    )
    assert marked_sha != unmarked_sha

    # The crash scenario: the commit reached git, the ledger write never ran.
    manager = PatchManager(config, repo_root=repo, clock=clock)
    assert manager.used() == 0
    assert manager.reserve(TASK) is not None, "1 trailer commit < cap of 2"
    recovered = [
        entry for entry in read_ledger(data_dir)["entries"] if entry["status"] == STATUS_COMMITTED
    ]
    assert [entry["commit_sha"] for entry in recovered] == [marked_sha]
    assert recovered[0]["task_id"] == TASK and recovered[0]["pid"] == 0
    assert all(entry["commit_sha"] != unmarked_sha for entry in recovered)

    # The recovered commit already burns one of the two slots; the live
    # reservation burns the second, so the cap refuses the next one.
    assert manager.used() == 2
    assert manager.reserve(TASK) is None
    # Releasing the live reservation frees exactly one slot again.
    live = entries_by_status(data_dir, STATUS_RESERVED)
    assert len(live) == 1
    assert manager.release(live[0]["token"], "unit-test") is True
    assert manager.snapshot()["used"] == 1, "the unmarked human commit never counts"
    assert manager.reserve(TASK) is not None

    # Reconciliation is idempotent: a second pass adds nothing.
    assert manager.reconcile() == 0

    # git unavailable degrades to "skip the rebuild", never to "skip the cap".
    offline = PatchManager(
        make_config(tmp_path / "offline", limit=1),
        repo_root=tmp_path / "not-a-repo",
        clock=clock,
    )
    assert offline.reserve(TASK) is not None
    assert offline.reserve(TASK) is None, "the cap still applies without git"

    # Messages are rendered with the stable trailer exactly once (REQ-04).
    message = commit_message_with_trailer(TASK, "fix: post-task review", "details")
    assert message.endswith(f"{MAINTAINER_COMMIT_TRAILER}: {TASK}")
    assert message.count(MAINTAINER_COMMIT_TRAILER) == 1
    assert commit_message_with_trailer(TASK, message) == message


# ---------------------------------------------------------------------------
# DOD-05 / REQ-05: corrupt ledger is quarantined and fails closed
# ---------------------------------------------------------------------------


def test_corrupt_ledger_quarantined_fail_closed(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    clock = Clock()
    repo = init_repo(tmp_path)
    data_dir = tmp_path / "data"
    data_dir.mkdir(parents=True)
    config = make_config(data_dir, limit=1)
    marked_sha = commit_file(
        repo,
        "marked.txt",
        f"feat: diagnosis\n\n{MAINTAINER_COMMIT_TRAILER}: {TASK}",
        window_stamp(clock),
    )

    ledger_file = data_dir / LEDGER_NAME
    ledger_file.write_text("{ this is not json", encoding="utf-8")

    with caplog.at_level("WARNING"):
        manager = PatchManager(config, repo_root=repo, clock=clock)
        assert manager.reserve(TASK) is None, "the rebuild finds one commit = the cap"

    assert any(LEDGER_CORRUPT in record.getMessage() for record in caplog.records)

    # Quarantined beside the ledger, and the rebuilt window is valid + counted.
    quarantined = sorted(data_dir.glob(f"{LEDGER_NAME}.quarantined.*"))
    assert len(quarantined) == 1
    assert quarantined[0].read_text(encoding="utf-8") == "{ this is not json"
    assert [str(path) for path in manager.ledger.quarantined_paths] == [str(quarantined[0])]
    rebuilt = read_ledger(data_dir)["entries"]
    assert [entry["commit_sha"] for entry in rebuilt] == [marked_sha]
    assert rebuilt[0]["status"] == STATUS_COMMITTED

    # Fail closed without git too: the window starts empty but the cap applies.
    blind_dir = tmp_path / "blind"
    blind_dir.mkdir()
    (blind_dir / LEDGER_NAME).write_text("", encoding="utf-8")  # torn/blank write
    blind = PatchManager(make_config(blind_dir, limit=1), clock=clock)
    assert blind.reserve(TASK) is not None, "nothing to rebuild from git"
    assert len(blind.ledger.quarantined_paths) == 1, "a blank ledger is corrupt too"
    assert blind.reserve(TASK) is None, "an unrebuildable window is never unlimited"

    # A structurally wrong document is corrupt as well (fail closed, no silent
    # drop of the receipts it might have contained).
    shape_dir = tmp_path / "shape"
    shape_dir.mkdir()
    (shape_dir / LEDGER_NAME).write_text('{"entries": "nope"}', encoding="utf-8")
    shaped = PatchManager(make_config(shape_dir, limit=1), clock=clock)
    assert shaped.reserve(TASK) is not None
    assert len(shaped.ledger.quarantined_paths) == 1

    # REQ-05: the explicit ``max_commits_per_hour <= 0`` bypass still wins.
    bypass_dir = tmp_path / "bypass"
    bypass_dir.mkdir()
    (bypass_dir / LEDGER_NAME).write_text(
        json.dumps(
            {
                "entries": [
                    {
                        "ts": clock.now,
                        "status": STATUS_COMMITTED,
                        "pid": 1,
                        "task_id": OTHER_TASK,
                        "commit_sha": "9" * 40,
                    }
                    for _ in range(10)
                ]
            }
        ),
        encoding="utf-8",
    )
    for limit in (0, -1):
        bypass = PatchManager(make_config(bypass_dir, limit=limit), clock=clock)
        assert bypass.snapshot()["bypass"] is True
        assert bypass.reserve(TASK) is not None
        assert bypass.reserve(TASK) is not None


# ---------------------------------------------------------------------------
# DOD-06 / REQ-05: the config knob through all five layers
# ---------------------------------------------------------------------------


def test_max_commits_per_hour_config(tmp_path: Path) -> None:
    # Layer 1: built-in default is the bypass.
    assert MaintainerConfig().max_commits_per_hour == 0

    # Layer 2: must_config.py is a soft dependency; emulate the merge site.
    from ai_maintainer import config as config_module

    merged = config_module._coerce_mapping({"max_commits_per_hour": "4"}, "must_config.py")
    assert merged == {"max_commits_per_hour": 4}

    # Layer 3: <maintainer_root>/config.json.
    (tmp_path / "config.json").write_text(
        json.dumps({"max_commits_per_hour": 5}), encoding="utf-8"
    )
    assert (
        MaintainerConfig.load(maintainer_root=tmp_path, env={}).max_commits_per_hour == 5
    )

    # Layer 4: MAINTAINER_* (and the AI_MAINTAINER_* alias).
    assert (
        MaintainerConfig.load(
            maintainer_root=tmp_path, env={"MAINTAINER_MAX_COMMITS_PER_HOUR": "7"}
        ).max_commits_per_hour
        == 7
    )
    assert (
        MaintainerConfig.load(
            maintainer_root=tmp_path, env={"AI_MAINTAINER_MAX_COMMITS_PER_HOUR": "8"}
        ).max_commits_per_hour
        == 8
    )

    # Layer 5: keyword override ("CLI") wins over the lower layers.
    assert (
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={"MAINTAINER_MAX_COMMITS_PER_HOUR": "7"},
            max_commits_per_hour=3,
        ).max_commits_per_hour
        == 3
    )

    # ``bool`` and non-integer values are rejected by the layer coercion.
    for layer in (
        {"MAINTAINER_MAX_COMMITS_PER_HOUR": "true"},
        {"MAINTAINER_MAX_COMMITS_PER_HOUR": "many"},
    ):
        with pytest.raises(ValueError):
            MaintainerConfig.load(maintainer_root=tmp_path, env=layer)
    with pytest.raises(ValueError):
        MaintainerConfig(max_commits_per_hour=True)
    with pytest.raises(ValueError):
        MaintainerConfig(max_commits_per_hour="3")

    # ``0`` and negative values are the legal bypass: no validate() issue.
    for limit in (0, -1):
        knobs = MaintainerConfig(max_commits_per_hour=limit)
        issues = knobs.validate()
        assert not [issue for issue in issues if "max_commits_per_hour" in issue]
    # And an absurdly large cap is equally legal.
    assert MaintainerConfig(max_commits_per_hour=10_000).max_commits_per_hour == 10_000


# ---------------------------------------------------------------------------
# Ledger/manager identity helpers (kept tiny; they guard against regressions in
# the wiring the DOD rows above rely on).
# ---------------------------------------------------------------------------


def test_ledger_reuses_cross_process_lock_file(tmp_path: Path) -> None:
    config = make_config(tmp_path / "data", limit=1)
    ledger = CommitLedger(ledger_path(config), clock=Clock())
    token = ledger.reserve(TASK, limit=1)
    assert token is not None
    # The section lock is the *sibling* of the ledger, never the ledger inode
    # (writes are ``tmp + os.replace``), and it is reused, not unlinked.
    assert (ledger.path.parent / f".{ledger.path.name}.lock").exists()
    assert ledger.path.exists()
    assert not (ledger.path.parent / f".{ledger.path.name}.tmp").exists()
    assert ledger.snapshot()["used"] == 1
    assert ledger.consume(token, "1" * 40) is True
    assert ledger.committed_sha(TASK) == ["1" * 40]
    assert ledger.consume(token, "2" * 40) is False, "a consumed token is not reusable"
    assert CommitBudgetExhausted is not None  # exported for callers
