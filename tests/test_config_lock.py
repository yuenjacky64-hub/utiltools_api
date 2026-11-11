"""UTILTOOLS-102: ``MaintainerConfig.instance_lock_file`` + lock path anchoring (REQ-01)."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ai_maintainer.config import MaintainerConfig  # noqa: E402
from ai_maintainer.util.fs import resolve_lock_path  # noqa: E402


def test_default_lock_file() -> None:
    config = MaintainerConfig()
    assert config.instance_lock_file == "data/maintainer.lock"


def test_lock_file_override() -> None:
    config = MaintainerConfig(instance_lock_file="state/maintainer.lock")
    assert config.instance_lock_file == "state/maintainer.lock"


def test_blank_lock_file_disables_locking() -> None:
    state_root = Path("/tmp/x")
    repo_root = Path("/tmp/y")
    # A disabled lock resolves to no path at all.
    assert resolve_lock_path(None, state_root=state_root, repo_root=repo_root) is None
    assert (
        resolve_lock_path("", state_root=state_root, repo_root=repo_root) is None
    )


def test_relative_lock_anchors_to_state_root() -> None:
    state_root = Path("/tmp/utiltools-102")
    # A relative value anchors its final component to the state root.
    lock = resolve_lock_path(
        "data/maintainer.lock", state_root=state_root, repo_root=Path("/tmp/y")
    )
    assert lock == state_root / "maintainer.lock"


def test_relative_lock_anchors_to_repo_root_without_state_root() -> None:
    repo_root = Path("/tmp/utiltools-102")
    lock = resolve_lock_path(
        "maintainer.lock", state_root=None, repo_root=repo_root
    )
    assert lock == repo_root / "maintainer.lock"


def test_absolute_lock_path_is_used_verbatim() -> None:
    lock = resolve_lock_path(
        "/var/lock/maintainer.lock",
        state_root=Path("/tmp/x"),
        repo_root=Path("/tmp/y"),
    )
    assert lock == Path("/var/lock/maintainer.lock")
