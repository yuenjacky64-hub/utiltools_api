"""Read-only pre-flight checks behind ``python3 cli.py check``.

The module answers one question — "is this maintainer configuration usable
right now?" — without touching anything it inspects. Every probe is a read
(``is_dir``, ``read_text``, ``stat``); nothing is created, rewritten or
enqueued, and neither the engine nor the api module is imported (their import
time starts threads).

Layer resolution is duplicated here on purpose. ``MaintainerConfig.load``
validates eagerly: ``__post_init__`` raises on the first paid model and
``_coerce_mapping`` raises on any unknown ``config.json`` key, so ``load()``
can only ever report the *first* problem and never names the offending CLI.
:func:`resolve_layer_values` therefore reads the same layers tolerantly and
keeps :meth:`MaintainerConfig.load` for the single ``config.consistency`` row,
whose exception text is reported verbatim.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from ai_maintainer.config import (
    CONFIG_JSON_NAME,
    ENV_PREFIX,
    MAINTAINER_ROOT_ENV,
    MaintainerConfig,
    _default_maintainer_root,
    is_free_model,
)

STATUS_PASS = "PASS"
STATUS_FAIL = "FAIL"
STATUS_SKIP = "SKIP"
STATUS_WARN = "WARN"

DEFAULT_HEARTBEAT_STALE_SEC = 900.0
TERMINAL_SIGNALS = frozenset({"done", "rejected", "error"})
PATH_FIELDS = ("maintainer_root", "ai_team_root", "default_project_root")
MODEL_FIELDS = (("primary_cli", "primary_model"), ("secondary_cli", "secondary_model"))
LAYER_FIELDS = (
    "maintainer_root",
    "ai_team_root",
    "default_project_root",
    "primary_cli",
    "primary_model",
    "secondary_cli",
    "secondary_model",
)
CHECKED_CLI = "pi"

SIGNAL_FILE = "_signal.txt"
PROGRESS_FILE = "agent_progress.log"
ACTIVE_TASK_DIR = "active_task"
SIGNAL_READ_LIMIT = 4096

_UP_TOKENS = frozenset({"up", "running", "alive", "ok", "true", "healthy"})
_DOWN_TOKENS = frozenset({"down", "stopped", "dead", "false"})

SOURCE_WATCHER = "FrameworkWatcher.get_status"
SOURCE_DISK = "active_task"
SOURCE_NONE = "none"


@dataclass(frozen=True)
class CheckResult:
    """One row of the report: a stable id, a verdict and a single-line detail."""

    check_id: str
    status: str
    detail: str


@dataclass(frozen=True)
class FrameworkStatus:
    """Snapshot of the orchestrator, produced read-only by the probe."""

    orchestrator: str  # "up" | "down" | "unknown"
    queue_state: str  # "idle" | "busy" | "unknown"
    queue_depth: int | None
    active_tasks: int | None
    last_completed_task: str | None
    source: str
    heartbeat_age_sec: float | None
    active_task_id: str | None = None  # newest non-terminal task that has a _signal.txt


def _as_text(value: Any) -> str | None:
    """Coerce a layer value to a stripped string, or ``None`` when unusable."""

    if value is None:
        return None
    if isinstance(value, str):
        text = value.strip()
        return text or None
    return str(value)


def _layered_config_root(
    maintainer_root: str | Path | None, env: Mapping[str, str] | None
) -> Path:
    """Resolve the root used to discover ``<root>/config.json``.

    Order: explicit argument, ``MAINTAINER_ROOT``, then the package default
    (``config._default_maintainer_root``, i.e. the repo root). When the caller
    supplies ``env`` the ambient environment is never consulted, so a stray
    ``MAINTAINER_ROOT`` in the operator's shell cannot move the root.
    """

    if maintainer_root is not None:
        return Path(maintainer_root).expanduser()
    env_map: Mapping[str, str] = os.environ if env is None else env
    configured = env_map.get(MAINTAINER_ROOT_ENV)
    if configured:
        return Path(configured).expanduser()
    if env is None:
        return _default_maintainer_root()
    return Path(__file__).resolve().parents[2]


def resolve_layer_values(
    *,
    config_path: str | Path | None = None,
    maintainer_root: str | Path | None = None,
    env: Mapping[str, str] | None = None,
) -> dict[str, str | None]:
    """Merge the config layers for the seven values the checks need.

    Precedence, lowest first: ``<root>/config.json`` (or ``config_path``),
    ``MAINTAINER_*`` variables from ``env``, then the explicit keywords.
    Unknown keys are ignored here — ``config.consistency`` reports them.
    """

    values: dict[str, str | None] = {name: None for name in LAYER_FIELDS}
    root = _layered_config_root(maintainer_root, env)

    file_path: Path | None
    if config_path is not None:
        file_path = Path(config_path).expanduser()
    else:
        candidate = root / CONFIG_JSON_NAME
        file_path = candidate if candidate.is_file() else None
    if file_path is not None:
        try:
            raw = json.loads(file_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raw = None
        if isinstance(raw, dict):
            for name in LAYER_FIELDS:
                if name in raw:
                    values[name] = _as_text(raw[name])

    env_map: Mapping[str, str] = os.environ if env is None else env
    for name in LAYER_FIELDS:
        env_name = ENV_PREFIX + name.upper()
        if env_name in env_map:
            values[name] = _as_text(env_map[env_name])

    if maintainer_root is not None:
        values["maintainer_root"] = _as_text(str(maintainer_root))
    if values["maintainer_root"] is None:
        values["maintainer_root"] = str(root)
    return values


def resolve_path_fields(values: Mapping[str, str | None]) -> dict[str, Path | None]:
    """Turn the three raw path strings into ``Path`` objects (``None`` if unset)."""

    paths: dict[str, Path | None] = {}
    for field in PATH_FIELDS:
        raw = values.get(field)
        paths[field] = Path(raw).expanduser() if raw else None
    return paths


def resolve_state_root(paths: Mapping[str, Path | None]) -> Path | None:
    """First existing ``active_task`` directory, or ``None`` when neither root is set."""

    for field in ("default_project_root", "ai_team_root"):
        root = paths.get(field)
        if root is None:
            continue
        candidate = root / ACTIVE_TASK_DIR
        try:
            if candidate.is_dir():
                return candidate
        except OSError:  # pragma: no cover - defensive, is_dir swallows most
            continue
    return None


def check_paths(paths: Mapping[str, Path | None]) -> list[CheckResult]:
    """One ``paths.<field>`` row per configured path field."""

    results: list[CheckResult] = []
    for field in PATH_FIELDS:
        path = paths.get(field)
        check_id = f"paths.{field}"
        if path is None:
            results.append(CheckResult(check_id, STATUS_SKIP, "not configured"))
            continue
        try:
            is_dir = path.is_dir()
        except OSError:  # pragma: no cover - defensive
            is_dir = False
        if is_dir:
            results.append(CheckResult(check_id, STATUS_PASS, str(path)))
        else:
            results.append(
                CheckResult(check_id, STATUS_FAIL, f"{field}: not a directory: {path}")
            )
    return results


def check_config_consistency(
    *,
    config_path: str | Path | None = None,
    maintainer_root: str | Path | None = None,
    env: Mapping[str, str] | None = None,
) -> CheckResult:
    """Run ``MaintainerConfig.load`` and turn any raise into a FAIL row.

    The exception text is reported verbatim: ``unknown config key(s) in
    config.json: […]`` and ``primary_model is not a free model: …`` are the two
    operator-facing messages this row exists to surface.
    """

    try:
        MaintainerConfig.load(
            config_path=config_path, maintainer_root=maintainer_root, env=env
        )
    except Exception as exc:  # noqa: BLE001 - a pre-flight row must never traceback
        # ``MaintainerConfig.load`` raises ValueError/OSError for bad values and
        # paths, but ``_load_must_config`` imports the optional ``must_config``
        # module, so a present-but-broken one raises ImportError/SyntaxError
        # (or anything else at import time). Every raise becomes a FAIL row with
        # the verbatim message: REQ-05 asks for a clear message, not a traceback.
        return CheckResult("config.consistency", STATUS_FAIL, str(exc))
    return CheckResult(
        "config.consistency", STATUS_PASS, "configuration layers load cleanly"
    )


def check_models(values: Mapping[str, str | None]) -> list[CheckResult]:
    """One ``models.<field>`` row per model, tier-checked only when the CLI is ``pi``."""

    results: list[CheckResult] = []
    for cli_field, model_field in MODEL_FIELDS:
        check_id = f"models.{model_field}"
        cli = (values.get(cli_field) or "").strip().lower()
        model = values.get(model_field)
        if cli != CHECKED_CLI:
            label = cli or "unset"
            results.append(
                CheckResult(
                    check_id,
                    STATUS_SKIP,
                    f"{model_field}: cli not pi ({label}), tier not checked",
                )
            )
            continue
        if is_free_model(model):
            label = (model or "").strip() or "unset"
            results.append(
                CheckResult(check_id, STATUS_PASS, f"{model_field}: free for cli=pi ({label})")
            )
            continue
        results.append(
            CheckResult(
                check_id,
                STATUS_FAIL,
                f"{model_field}: not a free model for cli=pi: {model}",
            )
        )
    return results


def _mapping_get(raw: Any, *names: str) -> Any:
    """Read the first present key/attribute out of a watcher status object."""

    for name in names:
        if isinstance(raw, Mapping):
            if name in raw:
                return raw[name]
            continue
        if hasattr(raw, name):
            return getattr(raw, name)
    return None


def _normalise_orchestrator(token: Any) -> str:
    if isinstance(token, bool):
        return "up" if token else "down"
    text = str(token or "").strip().lower()
    if text in _UP_TOKENS:
        return "up"
    if text in _DOWN_TOKENS:
        return "down"
    return "unknown"


def _int_or_none(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _status_from_watcher(raw: Any) -> FrameworkStatus:
    """Map a duck-typed ``get_status()`` result onto :class:`FrameworkStatus`."""

    orchestrator = _normalise_orchestrator(
        _mapping_get(raw, "orchestrator", "status", "state")
    )
    depth = _int_or_none(_mapping_get(raw, "queue_depth", "queue_state_depth"))
    active = _int_or_none(
        _mapping_get(raw, "active_tasks", "active_task_count", "active")
    )
    last = _mapping_get(raw, "last_completed_task", "last_completed")
    if depth is None:
        queue_state = "unknown"
    else:
        queue_state = "idle" if depth == 0 else "busy"
    return FrameworkStatus(
        orchestrator=orchestrator,
        queue_state=queue_state,
        queue_depth=depth,
        active_tasks=active,
        last_completed_task=str(last) if last else None,
        source=SOURCE_WATCHER,
        heartbeat_age_sec=None,
    )


def _first_signal_token(text: str) -> str | None:
    for line in text.splitlines():
        token = line.strip()
        if token:
            return token.split()[0].lower()
    return None


def _scan_active_task(
    state_root: Path, *, now: float, stale_sec: float
) -> FrameworkStatus:
    """Read-only scan of ``<state_root>/<task>/`` for liveness and queue depth."""

    queue_depth = 0
    active_tasks = 0
    last_completed: str | None = None
    last_completed_mtime: float | None = None
    active_task_id: str | None = None
    active_id_mtime: float | None = None
    min_age: float | None = None

    try:
        children = sorted(state_root.iterdir())
    except OSError:  # pragma: no cover - defensive
        children = []

    for child in children:
        try:
            if not child.is_dir():
                continue
        except OSError:  # pragma: no cover - defensive
            continue

        signal_path = child / SIGNAL_FILE
        progress_path = child / PROGRESS_FILE
        mtimes: list[float] = []
        token: str | None = None
        has_signal = False

        try:
            if signal_path.is_file():
                has_signal = True
                mtimes.append(signal_path.stat().st_mtime)
                with signal_path.open(encoding="utf-8", errors="replace") as handle:
                    # Only the leading token matters; bound the read so a rotated
                    # or oversized signal file cannot be loaded whole.
                    token = _first_signal_token(handle.read(SIGNAL_READ_LIMIT))
        except OSError:
            # A task directory can be reaped mid-scan; degrade, never raise.
            continue
        try:
            if progress_path.is_file():
                mtimes.append(progress_path.stat().st_mtime)
        except OSError:
            pass

        if mtimes:
            age = max(0.0, now - max(mtimes))
            min_age = age if min_age is None else min(min_age, age)

        if token in TERMINAL_SIGNALS:
            if token == "done" and mtimes:
                newest = max(mtimes)
                if last_completed_mtime is None or newest > last_completed_mtime:
                    last_completed_mtime = newest
                    last_completed = child.name
            continue
        if not has_signal:
            queue_depth += 1
            continue
        active_tasks += 1
        newest = max(mtimes) if mtimes else 0.0
        if active_id_mtime is None or newest > active_id_mtime or (
            newest == active_id_mtime
            and (active_task_id is None or child.name < active_task_id)
        ):
            active_id_mtime = newest
            active_task_id = child.name

    if min_age is None:
        orchestrator = "down"
    else:
        orchestrator = "up" if min_age <= stale_sec else "down"

    return FrameworkStatus(
        orchestrator=orchestrator,
        queue_state="idle" if queue_depth == 0 else "busy",
        queue_depth=queue_depth,
        active_tasks=active_tasks,
        last_completed_task=last_completed,
        source=SOURCE_DISK,
        heartbeat_age_sec=min_age,
        active_task_id=active_task_id,
    )


def probe_framework_status(
    *,
    state_root: Path | None,
    now: float | None = None,
    stale_sec: float = DEFAULT_HEARTBEAT_STALE_SEC,
) -> FrameworkStatus:
    """Report orchestrator liveness, preferring a real watcher when importable.

    ``support.FrameworkWatcher.get_status()`` is the brief's named API; it is
    optional in this repo, so the import is local and any failure falls through
    to the read-only ``active_task`` scan. When neither source exists the status
    is ``unknown`` (a WARN row, never a FAIL): "no framework on this host" is
    not "framework down".
    """

    try:
        import support  # type: ignore[import-not-found]  # optional, duck-typed
    except Exception:  # pragma: no cover - ImportError in every test run
        support = None  # type: ignore[assignment]
    if support is not None:
        watcher = getattr(support, "FrameworkWatcher", None)
        get_status = getattr(watcher, "get_status", None)
        if callable(get_status):
            try:
                return _status_from_watcher(get_status())
            except Exception:
                pass

    if state_root is None:
        return FrameworkStatus(
            orchestrator="unknown",
            queue_state="unknown",
            queue_depth=None,
            active_tasks=None,
            last_completed_task=None,
            source=SOURCE_NONE,
            heartbeat_age_sec=None,
            active_task_id=None,
        )
    return _scan_active_task(
        state_root, now=time.time() if now is None else now, stale_sec=stale_sec
    )


def check_framework(
    status: FrameworkStatus, *, stale_sec: float = DEFAULT_HEARTBEAT_STALE_SEC
) -> CheckResult:
    """Turn a :class:`FrameworkStatus` into the ``framework.orchestrator`` row."""

    if status.orchestrator == "up":
        age = status.heartbeat_age_sec
        age_text = "n/a" if age is None else f"{age:.1f}s"
        return CheckResult(
            "framework.orchestrator",
            STATUS_PASS,
            f"up (source={status.source}, heartbeat_age={age_text})",
        )
    if status.orchestrator == "down":
        return CheckResult(
            "framework.orchestrator",
            STATUS_FAIL,
            f"framework: orchestrator down (no heartbeat within {stale_sec}s)",
        )
    return CheckResult(
        "framework.orchestrator",
        STATUS_WARN,
        "unknown (no FrameworkWatcher and no active_task directory)",
    )


def run_checks(
    *,
    config_path: str | Path | None = None,
    maintainer_root: str | Path | None = None,
    env: Mapping[str, str] | None = None,
    now: float | None = None,
) -> tuple[list[CheckResult], FrameworkStatus]:
    """Evaluate every check in report order and return the results plus status."""

    values = resolve_layer_values(
        config_path=config_path, maintainer_root=maintainer_root, env=env
    )
    paths = resolve_path_fields(values)
    results: list[CheckResult] = []
    results.extend(check_paths(paths))
    results.append(
        check_config_consistency(
            config_path=config_path, maintainer_root=paths.get("maintainer_root"), env=env
        )
    )
    results.extend(check_models(values))
    status = probe_framework_status(state_root=resolve_state_root(paths), now=now)
    results.append(check_framework(status))
    return results, status


def _queue_line(status: FrameworkStatus) -> str:
    depth = "n/a" if status.queue_depth is None else str(status.queue_depth)
    active = "n/a" if status.active_tasks is None else str(status.active_tasks)
    last = status.last_completed_task or "none"
    return (
        f"{status.queue_state} ({depth} queued, {active} active, "
        f"last completed {last})"
    )


def format_report(results: list[CheckResult], status: FrameworkStatus) -> str:
    """Render the operator-facing report: one line per check plus the summary."""

    width = max((len(result.check_id) for result in results), default=0)
    width = max(width, len("framework.queue"))
    lines: list[str] = []
    for result in results:
        lines.append(f"[{result.status}] {result.check_id.ljust(width)}  {result.detail}")
        if result.check_id == "framework.orchestrator":
            lines.append(
                f"[INFO] {'framework.queue'.ljust(width)}  {_queue_line(status)}"
            )
    passed = sum(1 for result in results if result.status == STATUS_PASS)
    failed = sum(1 for result in results if result.status == STATUS_FAIL)
    skipped = sum(1 for result in results if result.status == STATUS_SKIP)
    warned = sum(1 for result in results if result.status == STATUS_WARN)
    lines.append(
        f"check: {passed} passed, {failed} failed, {skipped} skipped, {warned} warnings"
    )
    return "\n".join(lines)


def format_failures(results: list[CheckResult]) -> str:
    """Failure block for stderr: ``<check_id>: <detail>`` per FAIL, else empty."""

    return "\n".join(
        f"{result.check_id}: {result.detail}"
        for result in results
        if result.status == STATUS_FAIL
    )


def exit_code(results: list[CheckResult]) -> int:
    """``1`` iff at least one FAIL row, else ``0``."""

    return 1 if any(result.status == STATUS_FAIL for result in results) else 0
