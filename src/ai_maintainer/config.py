"""Configuration knobs for the maintainer task lifecycle.

All tuning values live here so the curriculum and the engine never hard-code a
threshold. Units follow the brief verbatim: the aging interval is expressed in
milliseconds (``prioritization_aging_interval_ms``) while the injected clock
reports seconds, so the single conversion site is
:attr:`MaintainerConfig.aging_threshold_seconds`.

The values can come from five layers, merged lowest-precedence first by
:meth:`MaintainerConfig.load`: built-in defaults < ``must_config.py`` <
``<maintainer_root>/config.json`` < ``MAINTAINER_*`` environment variables <
keyword overrides ("CLI"). Only ``load()`` reads files or the environment;
construction through ``MaintainerConfig(...)`` never does.
"""

from __future__ import annotations

import importlib.util
import json
import math
import os
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Any, Mapping

DEFAULT_AGING_INTERVAL_MS = 300_000
DEFAULT_RECENCY_BOOST_CYCLES = 3
DEFAULT_MAX_STEPS_PER_CYCLE = 8

DEFAULT_FINGERPRINT_QUARANTINE_THRESHOLD = 3
DEFAULT_FINGERPRINT_QUARANTINE_SEC = 60.0
DEFAULT_QUARANTINE_SWEEP_INTERVAL_SEC = 5.0
DEFAULT_BLOCKED_BACKOFF_BASE_SEC = 0.1
DEFAULT_BLOCKED_BACKOFF_MAX_SEC = 30.0
DEFAULT_MAX_FINGERPRINT_BUCKETS = 64

# Stale-task state machine thresholds (both in the unit the clock reports).
DEFAULT_STALE_TIMEOUT_SEC = 300.0
DEFAULT_STALE_RECOVERY_GRACE_SEC = 60.0

# Upper bound a graceful wind-down waits for an in-flight cycle (clock units).
DEFAULT_MAX_SHUTDOWN_WAIT_SEC = 300.0

DEFAULT_PRIMARY_CLI: str | None = None
DEFAULT_PRIMARY_MODEL: str | None = None
DEFAULT_SECONDARY_CLI: str | None = None
DEFAULT_SECONDARY_MODEL: str | None = None

# Fail-closed allowlist: an empty set means "no model is known to be free",
# never "every model is free". Register ids here or tag them with ``:free``.
FREE_MODEL_IDS: frozenset[str] = frozenset()
FREE_MODEL_SUFFIX = ":free"

MAINTAINER_ROOT_ENV = "MAINTAINER_ROOT"
ENV_PREFIX = "MAINTAINER_"
# Operator-facing spelling of the same layer (UTILTOOLS-73 DOD-03 names
# ``AI_MAINTAINER_MAX_SHUTDOWN_WAIT_SEC``); ``ENV_PREFIX`` wins on a tie so the
# long-standing convention keeps precedence.
ENV_ALIAS_PREFIX = "AI_" + ENV_PREFIX
CONFIG_JSON_NAME = "config.json"
MUST_CONFIG_MODULE = "must_config"
MUST_CONFIG_ATTRIBUTE = "MUST_CONFIG"

# Path knobs read by :meth:`MaintainerConfig.validate`. The ``*_CONFIG_PATH_FIELDS``
# names are deliberately distinct from ``checks.PATH_FIELDS`` (which only lists the
# three required roots) so a reader diffing the two modules is not misled.
REQUIRED_CONFIG_PATH_FIELDS: tuple[str, ...] = (
    "maintainer_root",
    "ai_team_root",
    "default_project_root",
)
OPTIONAL_CONFIG_PATH_FIELDS: tuple[str, ...] = ("data_dir", "reports_dir")
CONFIG_PATH_FIELDS: tuple[str, ...] = (
    REQUIRED_CONFIG_PATH_FIELDS + OPTIONAL_CONFIG_PATH_FIELDS
)

_TRUE_TOKENS = frozenset({"1", "true", "yes", "on"})
_FALSE_TOKENS = frozenset({"0", "false", "no", "off"})


def is_free_model(model: str | None) -> bool:
    """Return ``True`` when ``model`` is unset, allowlisted or ``:free`` tagged."""

    if model is None:
        return True
    if not isinstance(model, str):
        return False
    if not model.strip():
        return True
    if model in FREE_MODEL_IDS:
        return True
    return model.endswith(FREE_MODEL_SUFFIX)


def load_config_file(path: str | Path) -> dict[str, Any]:
    """Read a JSON config file and validate its keys and scalar types."""

    file_path = Path(path)
    raw = file_path.read_text(encoding="utf-8")
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:  # pragma: no cover - message passthrough
        raise ValueError(f"config.json is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError("config.json must contain a JSON object")
    return _coerce_mapping(data, "config.json")


def _default_maintainer_root() -> Path:
    """Resolve the maintainer root lazily, per call (never at import time)."""

    configured = os.environ.get(MAINTAINER_ROOT_ENV)
    if configured:
        return Path(configured).expanduser()
    return Path(__file__).resolve().parents[2]


def _load_must_config() -> dict[str, Any]:
    """Optional soft dependency: a top-level ``must_config.MUST_CONFIG`` mapping."""

    try:
        spec = importlib.util.find_spec(MUST_CONFIG_MODULE)
    except ModuleNotFoundError:
        return {}
    if spec is None:
        return {}
    module = importlib.import_module(MUST_CONFIG_MODULE)
    raw = getattr(module, MUST_CONFIG_ATTRIBUTE, None)
    if not raw:
        return {}
    if not isinstance(raw, Mapping):
        raise ValueError("must_config.py must provide a mapping")
    return _coerce_mapping({str(key).lower(): value for key, value in raw.items()}, "must_config.py")


def _coerce_mapping(raw: Mapping[str, Any], source: str) -> dict[str, Any]:
    """Validate keys against the dataclass fields and coerce scalar types."""

    known = {field.name for field in fields(MaintainerConfig)}
    unknown = sorted(key for key in raw if key not in known)
    if unknown:
        raise ValueError(f"unknown config key(s) in {source}: {unknown}")

    coerced: dict[str, Any] = {}
    for key, value in raw.items():
        if key in (
            "prioritization_aging_interval_ms",
            "recency_boost_cycles",
            "max_steps_per_cycle",
            "fingerprint_quarantine_threshold",
            "max_fingerprint_buckets",
        ):
            if isinstance(value, bool):
                raise ValueError(f"{key} must be an integer")
            try:
                coerced[key] = int(value)
            except (TypeError, ValueError) as exc:
                raise ValueError(f"{key} must be an integer") from exc
        elif key in (
            "fingerprint_quarantine_sec",
            "quarantine_sweep_interval_sec",
            "blocked_backoff_base_sec",
            "blocked_backoff_max_sec",
            "stale_timeout_sec",
            "stale_recovery_grace_sec",
            "max_shutdown_wait_sec",
        ):
            if isinstance(value, bool):
                raise ValueError(f"{key} must be a number")
            try:
                coerced[key] = float(value)
            except (TypeError, ValueError) as exc:
                raise ValueError(f"{key} must be a number") from exc
        elif key == "priority_preemption_enabled":
            coerced[key] = _coerce_bool(key, value)
        elif key in CONFIG_PATH_FIELDS:
            coerced[key] = _coerce_path_text(key, value)
        else:
            coerced[key] = value
    return coerced


def _coerce_path_text(key: str, value: Any) -> str | None:
    """Normalise a path layer value to a trimmed string, or ``None``."""

    if value is None:
        return None
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, str):
        return value.strip() or None
    raise ValueError(f"{key} must be a path string")


def _coerce_bool(key: str, value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        token = value.strip().lower()
        if token in _TRUE_TOKENS:
            return True
        if token in _FALSE_TOKENS:
            return False
    raise ValueError(f"{key} must be a boolean")


def _env_layer(env: Mapping[str, str]) -> dict[str, Any]:
    """Build the env-var layer; absent variables are omitted, never ``None``."""

    candidate: dict[str, Any] = {}
    for field in fields(MaintainerConfig):
        env_name = ENV_PREFIX + field.name.upper()
        if env_name in env:
            candidate[field.name] = env[env_name]
            continue
        alias_name = ENV_ALIAS_PREFIX + field.name.upper()
        if alias_name in env:
            candidate[field.name] = env[alias_name]
    return _coerce_mapping(candidate, "environment")


@dataclass(frozen=True)
class MaintainerConfig:
    """Immutable configuration for priority, preemption and aging."""

    prioritization_aging_interval_ms: int = DEFAULT_AGING_INTERVAL_MS
    recency_boost_cycles: int = DEFAULT_RECENCY_BOOST_CYCLES
    priority_preemption_enabled: bool = True
    max_steps_per_cycle: int = DEFAULT_MAX_STEPS_PER_CYCLE
    primary_cli: str | None = DEFAULT_PRIMARY_CLI
    primary_model: str | None = DEFAULT_PRIMARY_MODEL
    secondary_cli: str | None = DEFAULT_SECONDARY_CLI
    secondary_model: str | None = DEFAULT_SECONDARY_MODEL
    fingerprint_quarantine_threshold: int = DEFAULT_FINGERPRINT_QUARANTINE_THRESHOLD
    fingerprint_quarantine_sec: float = DEFAULT_FINGERPRINT_QUARANTINE_SEC
    quarantine_sweep_interval_sec: float = DEFAULT_QUARANTINE_SWEEP_INTERVAL_SEC
    blocked_backoff_base_sec: float = DEFAULT_BLOCKED_BACKOFF_BASE_SEC
    blocked_backoff_max_sec: float = DEFAULT_BLOCKED_BACKOFF_MAX_SEC
    max_fingerprint_buckets: int = DEFAULT_MAX_FINGERPRINT_BUCKETS
    stale_timeout_sec: float = DEFAULT_STALE_TIMEOUT_SEC
    stale_recovery_grace_sec: float = DEFAULT_STALE_RECOVERY_GRACE_SEC
    max_shutdown_wait_sec: float = DEFAULT_MAX_SHUTDOWN_WAIT_SEC

    # Path knobs (see ``CONFIG_PATH_FIELDS``). Stored as trimmed ``str | None`` so
    # the single ``Path(...)``/``expanduser()`` conversion site lives in
    # :meth:`validate` and the frozen dataclass never needs ``object.__setattr__``.
    maintainer_root: str | None = None
    ai_team_root: str | None = None
    default_project_root: str | None = None
    data_dir: str | None = None
    reports_dir: str | None = None

    def __post_init__(self) -> None:
        interval = self.prioritization_aging_interval_ms
        if not isinstance(interval, int) or isinstance(interval, bool) or interval < 0:
            raise ValueError("prioritization_aging_interval_ms must be >= 0")
        recency = self.recency_boost_cycles
        if not isinstance(recency, int) or isinstance(recency, bool) or recency < 1:
            raise ValueError("recency_boost_cycles must be >= 1")
        steps = self.max_steps_per_cycle
        if not isinstance(steps, int) or isinstance(steps, bool) or steps < 1:
            raise ValueError("max_steps_per_cycle must be >= 1")
        for name in ("primary_cli", "secondary_cli"):
            value = getattr(self, name)
            if value is not None and (not isinstance(value, str) or not value.strip()):
                raise ValueError(f"{name} must be a non-empty string")
        for name in ("primary_model", "secondary_model"):
            value = getattr(self, name)
            if value is not None and not is_free_model(value):
                raise ValueError(f"{name} is not a free model: {value}")
        threshold = self.fingerprint_quarantine_threshold
        if (
            not isinstance(threshold, int)
            or isinstance(threshold, bool)
            or threshold < 1
        ):
            raise ValueError("fingerprint_quarantine_threshold must be >= 1")
        for name in ("fingerprint_quarantine_sec", "quarantine_sweep_interval_sec"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
                raise ValueError(f"{name} must be > 0")
        base_backoff = self.blocked_backoff_base_sec
        if (
            isinstance(base_backoff, bool)
            or not isinstance(base_backoff, (int, float))
            or base_backoff < 0
        ):
            raise ValueError("blocked_backoff_base_sec must be >= 0")
        max_backoff = self.blocked_backoff_max_sec
        if (
            isinstance(max_backoff, bool)
            or not isinstance(max_backoff, (int, float))
            or max_backoff < base_backoff
        ):
            raise ValueError("blocked_backoff_max_sec must be >= blocked_backoff_base_sec")
        buckets = self.max_fingerprint_buckets
        if not isinstance(buckets, int) or isinstance(buckets, bool) or buckets < 1:
            raise ValueError("max_fingerprint_buckets must be >= 1")
        stale_timeout = self.stale_timeout_sec
        if (
            isinstance(stale_timeout, bool)
            or not isinstance(stale_timeout, (int, float))
            or stale_timeout <= 0
        ):
            raise ValueError("stale_timeout_sec must be > 0")
        stale_grace = self.stale_recovery_grace_sec
        if (
            isinstance(stale_grace, bool)
            or not isinstance(stale_grace, (int, float))
            or stale_grace < 0
        ):
            raise ValueError("stale_recovery_grace_sec must be >= 0")
        shutdown_wait = self.max_shutdown_wait_sec
        if (
            isinstance(shutdown_wait, bool)
            or not isinstance(shutdown_wait, (int, float))
            or not math.isfinite(float(shutdown_wait))
            or float(shutdown_wait) < 0.0
        ):
            raise ValueError("max_shutdown_wait_sec must be a finite float >= 0")

    @property
    def aging_threshold_seconds(self) -> float:
        """Aging threshold in the unit the clock reports (seconds)."""

        return self.prioritization_aging_interval_ms / 1000.0

    def validate(self) -> list[str]:
        """Return path issues for this configuration; ``[]`` when all paths are usable.

        Pure read: inspects ``self`` only (no environment, no ``config.json``
        reload, no directory creation), so the answer is deterministic and the
        frozen instance is never mutated.

        Required fields (``REQUIRED_CONFIG_PATH_FIELDS``: ``maintainer_root``,
        ``ai_team_root``, ``default_project_root``) are reported when unset;
        optional fields (``OPTIONAL_CONFIG_PATH_FIELDS``: ``data_dir``,
        ``reports_dir``) are checked with the same rules when configured and
        skipped when unset, mirroring ``checks.check_paths``' "not configured"
        skip row.

        Fields are visited in ``CONFIG_PATH_FIELDS`` order and one message per
        field is emitted, the first failing rule winning in this fixed order:

        * ``<field>: not configured`` - required field is ``None`` or blank
        * ``<field>: path could not be expanded: <text>`` - ``expanduser()`` failed
        * ``<field>: path is not absolute: <path>``
        * ``<field>: not a directory: <path>``
        * ``<field>: not accessible: <path>`` - ``os.R_OK | os.X_OK`` fails

        Every filesystem or proxy call sits behind a guard, so the method is
        total: for any field content it returns a ``list[str]`` and never raises.

        ``<path>`` echoes the configured spelling with ``~`` expanded, never
        ``Path.resolve()``-d, so the message matches what the operator wrote.
        """

        issues: list[str] = []
        for name in CONFIG_PATH_FIELDS:
            raw = getattr(self, name)
            text = None if raw is None else str(raw).strip()
            if not text:
                if name in REQUIRED_CONFIG_PATH_FIELDS:
                    issues.append(f"{name}: not configured")
                continue
            # INV-7: expanduser() is INSIDE the guard -- "~baduser/x" raises
            # RuntimeError, which no OSError-only handler catches.
            try:
                path = Path(text).expanduser()
                absolute = path.is_absolute()
            except (OSError, RuntimeError, ValueError):
                issues.append(f"{name}: path could not be expanded: {text}")
                continue
            if not absolute:
                issues.append(f"{name}: path is not absolute: {path}")
                continue
            try:
                is_dir = path.is_dir()
            except OSError:
                is_dir = False
            if not is_dir:
                issues.append(f"{name}: not a directory: {path}")
                continue
            try:
                usable = os.access(path, os.R_OK | os.X_OK)
            except OSError:  # pragma: no cover - defensive
                usable = False
            if not usable:
                issues.append(f"{name}: not accessible: {path}")
        return issues

    @classmethod
    def load(
        cls,
        *,
        config_path: str | Path | None = None,
        maintainer_root: str | Path | None = None,
        env: Mapping[str, str] | None = None,
        **overrides: Any,
    ) -> "MaintainerConfig":
        """Build a config from the five documented layers, lowest precedence first."""

        root = Path(maintainer_root) if maintainer_root is not None else _default_maintainer_root()

        file_path: Path | None
        if config_path is not None:
            file_path = Path(config_path).expanduser()
            if not file_path.is_file():
                raise FileNotFoundError(f"config path does not exist: {file_path}")
        else:
            candidate = root / CONFIG_JSON_NAME
            file_path = candidate if candidate.is_file() else None

        merged: dict[str, Any] = dict(_load_must_config())
        if file_path is not None:
            merged.update(load_config_file(file_path))
        merged.update(_env_layer(os.environ if env is None else env))
        merged.update(_coerce_mapping(overrides, "overrides"))
        # INV-5: the resolved root is the weakest layer -- an explicit
        # ``config.json`` key, ``MAINTAINER_MAINTAINER_ROOT`` or a keyword
        # override all win, so ``validate()`` sees the root the caller selected.
        merged.setdefault("maintainer_root", str(root))
        return cls(**merged)
