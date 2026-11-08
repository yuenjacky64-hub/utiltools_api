"""Read-only status report behind ``python3 cli.py status [--json]``.

``check`` is the verdict gate (it exits ``1`` on any FAIL row); ``status`` is a
reporter: it builds one payload dict and renders it either as human-readable
text or as a single JSON document, and always exits ``0`` once the report is
printed. Nothing here writes, creates a directory, spawns a process or opens a
socket. Every value comes from exactly one of three read-only places:

1. :func:`ai_maintainer.checks.run_checks` — the same probe ``check`` uses, so
   the orchestrator/queue/heartbeat view cannot drift from the gate;
2. this process' own memory accounting (``/proc/self/status`` and
   ``resource.getrusage``), never the daemon's;
3. the read-only ``<state_root>/maintainer_status.json`` counter document,
   which is optional input — absent or malformed it degrades to ``null`` values
   named in ``unavailable`` and never raises.

Both renderers consume the same payload dict (:func:`build_status_payload`), so
text and JSON output cannot disagree.
"""

from __future__ import annotations

import json
import math
import resource
import sys
import time
from pathlib import Path
from typing import Any, Mapping

from ai_maintainer import checks

STATUS_DOC_NAME = "maintainer_status.json"
STATUS_READ_LIMIT = 65536  # bound the read, mirrors checks.SIGNAL_READ_LIMIT

#: The eight field names the task brief names. Always present in the payload.
REQUIRED_FIELDS = (
    "daemon_status",
    "orchestrator_up",
    "active_task_id",
    "queue_state",
    "total_cycles",
    "bugs_patched",
    "total_learnings",
    "latest_task_run",
)

COUNTER_FIELDS = ("total_cycles", "bugs_patched", "total_learnings")

SOURCE_THIS_PROCESS = "this_process"
SOURCE_UNAVAILABLE = "unavailable"
SOURCE_ACTIVE_TASK = checks.SOURCE_DISK
SOURCE_NONE = checks.SOURCE_NONE

PROC_SELF_STATUS = Path("/proc/self/status")

_ORCHESTRATOR_UP = {"up": True, "down": False}


def resolve_status_doc(
    *,
    config_path: str | Path | None = None,
    maintainer_root: str | Path | None = None,
    env: Mapping[str, str] | None = None,
) -> Path | None:
    """``<state_root>/maintainer_status.json``, or ``None`` when no state root.

    The state root is resolved with exactly the helpers ``checks.run_checks``
    uses, so the counter document is looked for where the ``check`` probe looks
    for the ``active_task`` directory.
    """

    values = checks.resolve_layer_values(
        config_path=config_path, maintainer_root=maintainer_root, env=env
    )
    state_root = checks.resolve_state_root(checks.resolve_path_fields(values))
    if state_root is None:
        return None
    return state_root / STATUS_DOC_NAME


def _read_status_doc(path: Path | None) -> dict[str, Any] | None:
    """Read the counter document read-only; ``None`` when absent or unusable."""

    if path is None:
        return None
    # Same guard as the sibling probe (checks.py): a FIFO/device/socket at this
    # path would block ``open()`` forever, so only regular files are read.
    if not path.is_file():
        return None
    try:
        with path.open(encoding="utf-8", errors="replace") as handle:
            raw = json.loads(handle.read(STATUS_READ_LIMIT))
    # RecursionError: json's C scanner raises it for deeply nested input inside
    # the read window; it is not a ValueError, so it must be named here (INV-3).
    except (OSError, ValueError, RecursionError):
        return None
    if not isinstance(raw, dict):
        return None
    return raw


def _int_counter(doc: Mapping[str, Any] | None, name: str) -> int | None:
    """``doc[name]`` when it is a real ``int`` (``bool`` is not a counter)."""

    if doc is None:
        return None
    value = doc.get(name)
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def _float_or_none(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        number = float(value)
        # ``json.loads`` accepts bare NaN/Infinity; those are not JSON numbers.
        return number if math.isfinite(number) else None
    return None


def _latest_from_doc(doc: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """``latest_task_run`` when the counter document carries a usable object."""

    if doc is None:
        return None
    raw = doc.get("latest_task_run")
    if not isinstance(raw, dict):
        return None
    task_id = raw.get("task_id")
    if not isinstance(task_id, str) or not task_id:
        return None
    status = raw.get("status")
    return {
        "task_id": task_id,
        "status": status if isinstance(status, str) and status else "unknown",
        "completed_age_sec": _float_or_none(raw.get("completed_age_sec")),
    }


def _latest_from_disk(status: checks.FrameworkStatus) -> dict[str, Any] | None:
    """Fallback: the newest ``Done`` task the probe saw, with its heartbeat age."""

    if not status.last_completed_task:
        return None
    return {
        "task_id": status.last_completed_task,
        "status": "done",
        "completed_age_sec": status.heartbeat_age_sec,
    }


def _rss_kb() -> int | None:
    """``VmRSS`` of this process in kB, or ``None`` when ``/proc`` is absent."""

    try:
        text = PROC_SELF_STATUS.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for line in text.splitlines():
        if not line.startswith("VmRSS:"):
            continue
        parts = line.split()
        if len(parts) < 2:  # pragma: no cover - /proc is well formed
            raise ValueError(f"unparsable VmRSS line: {line!r}")
        return int(parts[1])
    return None


def _peak_rss_kb() -> int:
    """Peak RSS of this process in kB (``ru_maxrss`` is bytes on macOS)."""

    peak = int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    if sys.platform == "darwin":  # pragma: no cover - linux CI
        peak //= 1024
    return peak


def _memory_stats() -> dict[str, Any]:
    """Memory of *this* process (the one running ``status``), never the daemon."""

    try:
        rss_kb = _rss_kb()
        peak_kb = _peak_rss_kb()
    except (OSError, ValueError):
        return {"available": False, "rss_kb": None, "peak_rss_kb": None}
    if rss_kb is None and peak_kb is None:  # pragma: no cover - linux has /proc
        return {"available": False, "rss_kb": None, "peak_rss_kb": None}
    return {"available": True, "rss_kb": rss_kb, "peak_rss_kb": peak_kb}


def _daemon_status(status: checks.FrameworkStatus, stale_sec: float) -> str:
    """§4.2: heartbeat age in the daemon's own vocabulary."""

    age = status.heartbeat_age_sec
    if status.source == checks.SOURCE_NONE or age is None:
        return "unknown"
    return "running" if age <= stale_sec else "stale"


def build_status_payload(
    *,
    config_path: str | Path | None = None,
    maintainer_root: str | Path | None = None,
    env: Mapping[str, str] | None = None,
    now: float | None = None,
    stale_sec: float = checks.DEFAULT_HEARTBEAT_STALE_SEC,
) -> dict[str, Any]:
    """Build the one dict both renderers consume. Read-only by construction."""

    generated_at = time.time() if now is None else float(now)
    _results, status = checks.run_checks(
        config_path=config_path,
        maintainer_root=maintainer_root,
        env=env,
        now=generated_at,
    )
    doc = _read_status_doc(
        resolve_status_doc(
            config_path=config_path, maintainer_root=maintainer_root, env=env
        )
    )

    payload: dict[str, Any] = {
        "daemon_status": _daemon_status(status, stale_sec),
        "orchestrator_up": _ORCHESTRATOR_UP.get(status.orchestrator),
        "orchestrator_status": status.orchestrator,
        "active_task_id": status.active_task_id,
        "queue_state": status.queue_state,
        "queue_depth": status.queue_depth,
        "active_tasks": status.active_tasks,
        "heartbeat_age_sec": status.heartbeat_age_sec,
        "last_completed_task": status.last_completed_task,
        "memory": _memory_stats(),
        "total_cycles": _int_counter(doc, "total_cycles"),
        "bugs_patched": _int_counter(doc, "bugs_patched"),
        "total_learnings": _int_counter(doc, "total_learnings"),
    }

    latest = _latest_from_doc(doc)
    latest_source = "maintainer_status.json"
    if latest is None:
        latest = _latest_from_disk(status)
        latest_source = SOURCE_ACTIVE_TASK if latest is not None else SOURCE_NONE
    payload["latest_task_run"] = latest

    payload["generated_at"] = generated_at
    payload["sources"] = {
        "orchestrator": status.source,
        "counters": "maintainer_status.json" if doc is not None else SOURCE_UNAVAILABLE,
        "latest_task_run": latest_source,
        "memory": SOURCE_THIS_PROCESS,
    }
    payload["unavailable"] = sorted(
        name for name in (*COUNTER_FIELDS, "latest_task_run", "active_task_id")
        if payload[name] is None
    )
    return payload


def _age_text(value: Any) -> str:
    if value is None or isinstance(value, bool) or not isinstance(value, (int, float)):
        return "n/a"
    return f"{float(value):.1f}s"


def _int_text(value: Any) -> str:
    return "n/a" if value is None else str(value)


def _mib_text(value: Any) -> str:
    if value is None or isinstance(value, bool) or not isinstance(value, (int, float)):
        return "n/a"
    return f"{float(value) / 1024.0:.1f} MiB"


def _clean(value: Any, default: str = "unknown") -> str:
    """One printable line: a document-supplied string cannot forge extra lines."""

    if not isinstance(value, str) or not value.strip():
        return default
    cleaned = "".join(char if char.isprintable() else " " for char in value).strip()
    return cleaned or default


def format_text(payload: Mapping[str, Any]) -> str:
    """Render the payload for humans. Reads only the dict it is given (INV-6)."""

    memory = payload.get("memory") or {}
    if memory.get("available"):
        memory_line = f"rss {_mib_text(memory.get('rss_kb'))}, peak {_mib_text(memory.get('peak_rss_kb'))}"
    else:
        memory_line = "unavailable"

    latest = payload.get("latest_task_run")
    if isinstance(latest, Mapping):
        latest_line = (
            f"{_clean(latest.get('task_id'))} {_clean(latest.get('status'))} "
            f"(age {_age_text(latest.get('completed_age_sec'))})"
        )
    else:
        latest_line = "unknown"

    unavailable = payload.get("unavailable") or []
    unavailable_line = ", ".join(_clean(name) for name in unavailable) or "none"

    lines = [
        "maintainer status",
        f"daemon: {_clean(payload.get('daemon_status'))} "
        f"(source={_clean((payload.get('sources') or {}).get('orchestrator'))}, "
        f"heartbeat_age={_age_text(payload.get('heartbeat_age_sec'))})",
        f"orchestrator: {_clean(payload.get('orchestrator_status'))}",
        f"active task: {_clean(payload.get('active_task_id'))}",
        f"queue: {_clean(payload.get('queue_state'))} "
        f"({_int_text(payload.get('queue_depth'))} queued, "
        f"{_int_text(payload.get('active_tasks'))} active, "
        f"last completed {_clean(payload.get('last_completed_task'), 'none')})",
        f"memory: {memory_line}",
        f"counters: total_cycles={_int_text(payload.get('total_cycles'))} "
        f"bugs_patched={_int_text(payload.get('bugs_patched'))} "
        f"total_learnings={_int_text(payload.get('total_learnings'))}",
        f"latest task run: {latest_line}",
        f"unavailable: {unavailable_line}",
    ]
    return "\n".join(lines)


def _json_safe(value: Any) -> Any:
    """Copy of the payload without non-finite floats (``NaN`` is not JSON)."""

    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, Mapping):
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


def render_json(payload: Mapping[str, Any]) -> str:
    """One strictly valid JSON document (sorted keys, finite numbers per INV-2)."""

    return json.dumps(_json_safe(payload), sort_keys=True, allow_nan=False)
