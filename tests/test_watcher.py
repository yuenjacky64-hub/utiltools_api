"""``FrameworkWatcher.is_task_parked`` — the parked-status query (REQ-01..REQ-03).

Each test builds its snapshot by hand and calls the method directly, so nothing
here reads ``_signal.txt``, ``active_task`` or any other on-disk state, and no
test sleeps. The classification rule itself is exercised through the same path
a caller uses: the watcher's scan over ``ActiveTask.parked``.
"""

from __future__ import annotations

import inspect

from ai_maintainer.monitor.models import (
    PARKED_STATUS_PREFIX,
    ActiveTask,
    FrameworkSnapshot,
    is_parked_status,
)
from ai_maintainer.monitor.watcher import FrameworkWatcher


def _watcher(*tasks: ActiveTask) -> FrameworkWatcher:
    """A watcher over a snapshot holding exactly ``tasks``, in that order."""

    return FrameworkWatcher(FrameworkSnapshot(active_tasks=tuple(tasks)))


def _annotation_name(annotation: object) -> str:
    """Annotation text for a signature check, PEP 563 strings included."""

    if isinstance(annotation, str):
        return annotation
    return getattr(annotation, "__name__", str(annotation))


def test_import_and_signature() -> None:
    """DOD-01: the class and method import, call, and keep their contract."""

    watcher = FrameworkWatcher()
    assert isinstance(watcher.get_snapshot(), FrameworkSnapshot)
    assert isinstance(watcher.is_task_parked("T-1"), bool)
    assert watcher.is_task_parked("T-1") is False

    parameters = list(
        inspect.signature(FrameworkWatcher.is_task_parked).parameters.values()
    )
    assert [parameter.name for parameter in parameters] == ["self", "task_id"]
    assert _annotation_name(parameters[1].annotation) == "str"
    assert _annotation_name(
        inspect.signature(FrameworkWatcher.is_task_parked).return_annotation
    ) == "bool"


def test_awaiting_merge_is_parked() -> None:
    """DOD-02: a task sitting in 'Awaiting Merge' is parked."""

    watcher = _watcher(
        ActiveTask(task_id="T-1", status="Awaiting Merge"),
        ActiveTask(task_id="T-2", status="Running"),
    )
    assert watcher.is_task_parked("T-1") is True


def test_awaiting_prefix_is_parked() -> None:
    """DOD-03: every status starting with 'Awaiting' is parked."""

    assert is_parked_status(PARKED_STATUS_PREFIX) is True
    watcher = _watcher(
        ActiveTask(task_id="T-bare", status="Awaiting"),
        ActiveTask(task_id="T-qa", status="Awaiting QA"),
        ActiveTask(task_id="T-merge", status="Awaiting Merge"),
        ActiveTask(task_id="T-suffix", status="AwaitingHuman Review"),
    )
    for task_id in ("T-bare", "T-qa", "T-merge", "T-suffix"):
        assert watcher.is_task_parked(task_id) is True


def test_non_awaiting_status_is_not_parked() -> None:
    """DOD-04: non-parked statuses answer ``False``, case included."""

    watcher = _watcher(
        ActiveTask(task_id="T-running", status="Running"),
        ActiveTask(task_id="T-done", status="Done"),
        ActiveTask(task_id="T-rejected", status="Rejected"),
        ActiveTask(task_id="T-lower", status="awaiting merge"),
        ActiveTask(task_id="T-empty", status=""),
        ActiveTask(task_id="T-infix", status="Not Awaiting Merge"),
    )
    for task_id in (
        "T-running",
        "T-done",
        "T-rejected",
        "T-lower",
        "T-empty",
        "T-infix",
    ):
        assert watcher.is_task_parked(task_id) is False


def test_lookup_walks_active_tasks() -> None:
    """DOD-05: the answer comes from the matching task; unknown ids are False."""

    watcher = _watcher(
        ActiveTask(task_id="T-1", status="Running"),
        ActiveTask(task_id="T-2", status="Awaiting Merge"),
        ActiveTask(task_id="T-3", status="Done"),
    )
    assert watcher.is_task_parked("T-1") is False
    assert watcher.is_task_parked("T-2") is True
    assert watcher.is_task_parked("T-3") is False
    assert watcher.is_task_parked("T-absent") is False

    duplicated = _watcher(
        ActiveTask(task_id="T-dup", status="Running"),
        ActiveTask(task_id="T-dup", status="Awaiting Merge"),
    )
    assert duplicated.is_task_parked("T-dup") is False

    empty = FrameworkWatcher(FrameworkSnapshot())
    assert empty.is_task_parked("T-1") is False


def test_defensive_inputs_degrade_to_false() -> None:
    """DOD-06: bad statuses and bad task ids answer ``False``, never raise."""

    assert is_parked_status(None) is False
    assert is_parked_status(123) is False
    assert is_parked_status(b"Awaiting Merge") is False
    assert is_parked_status(["Awaiting Merge"]) is False

    watcher = _watcher(
        ActiveTask(task_id="T-none", status=None),  # type: ignore[arg-type]
        ActiveTask(task_id="T-int", status=123),  # type: ignore[arg-type]
        ActiveTask(task_id="T-ok", status="Awaiting Merge"),
    )
    assert watcher.is_task_parked("T-none") is False
    assert watcher.is_task_parked("T-int") is False
    assert watcher.is_task_parked("T-ok") is True

    assert watcher.is_task_parked("") is False
    assert watcher.is_task_parked(None) is False  # type: ignore[arg-type]
    assert watcher.is_task_parked(123) is False  # type: ignore[arg-type]
    assert watcher.is_task_parked(b"T-ok") is False  # type: ignore[arg-type]
