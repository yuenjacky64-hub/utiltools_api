"""Tests for the ``config.json`` auto-loading path of ``MaintainerConfig.load``.

Every test passes ``maintainer_root`` and ``env`` explicitly (INV-4) so an
ambient repo-root ``config.json`` or a developer's real environment can never
change the outcome.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

import ai_maintainer.config as config_module
from ai_maintainer.config import MaintainerConfig, is_free_model, load_config_file


def _write_config(root: Path, payload: Any) -> Path:
    path = root / "config.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


@pytest.fixture()
def empty_must_config(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config_module, "_load_must_config", lambda: {})


def test_load_autodetects_config_json_from_maintainer_root(
    tmp_path: Path, empty_must_config: None
) -> None:
    path = _write_config(
        tmp_path,
        {
            "primary_cli": "codex-cli",
            "primary_model": "tiny-model:free",
            "secondary_cli": "claude-cli",
            "secondary_model": "helper-model:free",
        },
    )

    config = MaintainerConfig.load(maintainer_root=tmp_path, env={})

    assert config.primary_cli == "codex-cli"
    assert config.primary_model == "tiny-model:free"
    assert config.secondary_cli == "claude-cli"
    assert config.secondary_model == "helper-model:free"
    # untouched fields keep their built-in defaults
    assert config.max_steps_per_cycle == 8
    assert config.prioritization_aging_interval_ms == 300_000
    assert load_config_file(path)["primary_cli"] == "codex-cli"


def test_load_without_config_json_falls_back_to_must_config(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert not (tmp_path / config_module.CONFIG_JSON_NAME).exists()
    monkeypatch.setattr(
        config_module,
        "_load_must_config",
        lambda: {"max_steps_per_cycle": 5, "primary_cli": "must-cli"},
    )

    config = MaintainerConfig.load(maintainer_root=tmp_path, env={})

    assert config.max_steps_per_cycle == 5
    assert config.primary_cli == "must-cli"
    assert config.recency_boost_cycles == 3

    monkeypatch.setattr(config_module, "_load_must_config", lambda: {})
    defaults = MaintainerConfig.load(maintainer_root=tmp_path, env={})
    # INV-5: load() records the resolved root; every non-path field stays default.
    assert defaults == MaintainerConfig(maintainer_root=str(tmp_path))


def test_explicit_config_path_beats_autodetected(
    tmp_path: Path, tmp_path_factory: pytest.TempPathFactory, empty_must_config: None
) -> None:
    _write_config(tmp_path, {"primary_cli": "auto-cli"})
    explicit_dir = tmp_path_factory.mktemp("explicit")
    explicit = explicit_dir / "explicit.json"
    explicit.write_text(json.dumps({"primary_cli": "explicit-cli"}), encoding="utf-8")

    config = MaintainerConfig.load(config_path=explicit, maintainer_root=tmp_path, env={})
    assert config.primary_cli == "explicit-cli"

    with pytest.raises(FileNotFoundError) as excinfo:
        MaintainerConfig.load(config_path=tmp_path / "missing.json", maintainer_root=tmp_path, env={})
    assert "config path does not exist: " in str(excinfo.value)


def test_config_json_rejects_non_free_model(
    tmp_path: Path, empty_must_config: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(config_module, "FREE_MODEL_IDS", frozenset())

    _write_config(tmp_path, {"primary_model": "paid-model"})
    with pytest.raises(ValueError) as excinfo:
        MaintainerConfig.load(maintainer_root=tmp_path, env={})
    assert "primary_model is not a free model: " in str(excinfo.value)

    _write_config(tmp_path, {"secondary_model": "paid-model"})
    with pytest.raises(ValueError) as excinfo:
        MaintainerConfig.load(maintainer_root=tmp_path, env={})
    assert "secondary_model is not a free model: " in str(excinfo.value)


def test_config_json_accepts_free_model(
    tmp_path: Path, empty_must_config: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(config_module, "FREE_MODEL_IDS", frozenset({"registered-free-model"}))

    _write_config(tmp_path, {"primary_model": "registered-free-model"})
    assert MaintainerConfig.load(maintainer_root=tmp_path, env={}).primary_model == "registered-free-model"

    _write_config(tmp_path, {"secondary_model": "anything:free"})
    assert MaintainerConfig.load(maintainer_root=tmp_path, env={}).secondary_model == "anything:free"

    assert is_free_model(None) is True
    assert is_free_model("anything:free") is True
    assert is_free_model("paid-model") is False


def test_precedence_ladder(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config_module, "_load_must_config", lambda: {"max_steps_per_cycle": 2})
    with_json = tmp_path / "with_json"
    with_json.mkdir()
    _write_config(with_json, {"max_steps_per_cycle": 3})
    bare = tmp_path / "bare"
    bare.mkdir()
    env = {"MAINTAINER_MAX_STEPS_PER_CYCLE": "4"}

    # CLI override > env > config.json > must_config.py > built-in default
    assert (
        MaintainerConfig.load(
            maintainer_root=with_json, env=env, max_steps_per_cycle=5
        ).max_steps_per_cycle
        == 5
    )
    assert MaintainerConfig.load(maintainer_root=with_json, env=env).max_steps_per_cycle == 4
    assert MaintainerConfig.load(maintainer_root=with_json, env={}).max_steps_per_cycle == 3
    assert MaintainerConfig.load(maintainer_root=bare, env={}).max_steps_per_cycle == 2

    monkeypatch.setattr(config_module, "_load_must_config", lambda: {})
    assert MaintainerConfig.load(maintainer_root=bare, env={}).max_steps_per_cycle == 8


def test_config_json_rejects_unknown_key(tmp_path: Path, empty_must_config: None) -> None:
    _write_config(tmp_path, {"unknown_knob": 1})
    with pytest.raises(ValueError) as excinfo:
        MaintainerConfig.load(maintainer_root=tmp_path, env={})
    assert "unknown config key(s) in config.json: " in str(excinfo.value)
    assert "unknown_knob" in str(excinfo.value)

    (tmp_path / "config.json").write_text("[1, 2, 3]", encoding="utf-8")
    with pytest.raises(ValueError) as excinfo:
        MaintainerConfig.load(maintainer_root=tmp_path, env={})
    assert "config.json must contain a JSON object" in str(excinfo.value)


def test_fingerprint_config_defaults_and_validation(
    tmp_path: Path, empty_must_config: None
) -> None:
    """DOD-11 / REQ-01: new knobs have safe defaults, are coerced and validated."""

    defaults = MaintainerConfig()
    assert defaults.fingerprint_quarantine_threshold == 3
    assert defaults.fingerprint_quarantine_sec == 60.0
    assert defaults.quarantine_sweep_interval_sec == 5.0
    assert defaults.blocked_backoff_base_sec == 0.1
    assert defaults.blocked_backoff_max_sec == 30.0
    assert defaults.max_fingerprint_buckets == 64

    # Env layer coerces the string forms to the declared numeric types.
    env = {
        "MAINTAINER_FINGERPRINT_QUARANTINE_SEC": "60",
        "MAINTAINER_QUARANTINE_SWEEP_INTERVAL_SEC": "2.5",
        "MAINTAINER_BLOCKED_BACKOFF_BASE_SEC": "1",
        "MAINTAINER_BLOCKED_BACKOFF_MAX_SEC": "7",
        "MAINTAINER_FINGERPRINT_QUARANTINE_THRESHOLD": "5",
        "MAINTAINER_MAX_FINGERPRINT_BUCKETS": "16",
    }
    config = MaintainerConfig.load(maintainer_root=tmp_path, env=env)
    assert config.fingerprint_quarantine_sec == 60.0
    assert isinstance(config.fingerprint_quarantine_sec, float)
    assert config.quarantine_sweep_interval_sec == 2.5
    assert config.blocked_backoff_base_sec == 1.0
    assert config.blocked_backoff_max_sec == 7.0
    assert config.fingerprint_quarantine_threshold == 5
    assert config.max_fingerprint_buckets == 16

    with pytest.raises(ValueError, match="fingerprint_quarantine_threshold must be >= 1"):
        MaintainerConfig(fingerprint_quarantine_threshold=0)
    with pytest.raises(ValueError, match="must be > 0"):
        MaintainerConfig(fingerprint_quarantine_sec=0.0)
    with pytest.raises(ValueError, match="must be > 0"):
        MaintainerConfig(quarantine_sweep_interval_sec=-1.0)
    with pytest.raises(ValueError, match="blocked_backoff_base_sec must be >= 0"):
        MaintainerConfig(blocked_backoff_base_sec=-0.5)
    with pytest.raises(ValueError, match="blocked_backoff_max_sec"):
        MaintainerConfig(blocked_backoff_base_sec=2.0, blocked_backoff_max_sec=1.0)
    with pytest.raises(ValueError, match="max_fingerprint_buckets must be >= 1"):
        MaintainerConfig(max_fingerprint_buckets=0)
    with pytest.raises(ValueError, match="must be an integer"):
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={"MAINTAINER_FINGERPRINT_QUARANTINE_THRESHOLD": "three"},
        )
    with pytest.raises(ValueError, match="must be a number"):
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={"MAINTAINER_FINGERPRINT_QUARANTINE_SEC": "soon"},
        )


def test_stale_knobs_default_and_validate(tmp_path: Path) -> None:
    """DOD-05 / REQ-02: ``POLL_STALE_TIMEOUT_SEC`` default, env and validation."""

    config = MaintainerConfig()
    assert config.stale_timeout_sec == 300.0
    assert isinstance(config.stale_timeout_sec, float)
    assert config.stale_recovery_grace_sec == 60.0

    env = {
        "MAINTAINER_STALE_TIMEOUT_SEC": "120",
        "MAINTAINER_STALE_RECOVERY_GRACE_SEC": "30",
    }
    loaded = MaintainerConfig.load(maintainer_root=tmp_path, env=env)
    assert loaded.stale_timeout_sec == 120.0
    assert loaded.stale_recovery_grace_sec == 30.0

    # ``0`` is a legal grace: recover as soon as the timeout is breached.
    assert MaintainerConfig(stale_recovery_grace_sec=0).stale_recovery_grace_sec == 0.0

    with pytest.raises(ValueError, match="stale_timeout_sec must be > 0"):
        MaintainerConfig(stale_timeout_sec=0.0)
    with pytest.raises(ValueError, match="stale_recovery_grace_sec must be >= 0"):
        MaintainerConfig(stale_recovery_grace_sec=-1.0)
    with pytest.raises(ValueError, match="must be a number"):
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={"MAINTAINER_STALE_TIMEOUT_SEC": "five minutes"},
        )


# --- UTILTOOLS-90: opencode_timeout_sec ------------------------------------


def _valid_path_kwargs(root: Path) -> dict[str, str]:
    """Create the configured path fields under ``root`` so ``validate()`` has no path issues."""

    created: dict[str, str] = {}
    for name in config_module.CONFIG_PATH_FIELDS:
        path = root / name
        path.mkdir(parents=True, exist_ok=True)
        created[name] = str(path)
    return created


def test_opencode_timeout_default_is_600() -> None:
    """DOD-05 / REQ-01: the dataclass field still defaults to 600 seconds."""

    assert config_module.DEFAULT_OPENCODE_TIMEOUT_SEC == 600
    config = MaintainerConfig()
    assert config.opencode_timeout_sec == 600
    assert isinstance(config.opencode_timeout_sec, int)


def test_validate_flags_nonpositive_opencode_timeout(tmp_path: Path) -> None:
    """DOD-01 / REQ-01: ``0`` and negatives are reported, not raised."""

    kwargs = _valid_path_kwargs(tmp_path)
    for value in (0, -5):
        issues = MaintainerConfig(**kwargs, opencode_timeout_sec=value).validate()
        assert issues == ["opencode_timeout_sec must be a positive integer"]

    # ``bool`` is an ``int`` subclass, so it must be rejected explicitly.
    issues = MaintainerConfig(**kwargs, opencode_timeout_sec=True).validate()
    assert issues == ["opencode_timeout_sec must be a positive integer"]


def test_validate_flags_out_of_range_opencode_timeout(tmp_path: Path) -> None:
    """DOD-02 / REQ-02: ``59`` and ``3601`` each produce one message."""

    kwargs = _valid_path_kwargs(tmp_path)
    assert MaintainerConfig(**kwargs, opencode_timeout_sec=59).validate() == [
        "opencode_timeout_sec must be >= 60"
    ]
    assert MaintainerConfig(**kwargs, opencode_timeout_sec=3601).validate() == [
        "opencode_timeout_sec must be <= 3600"
    ]


def test_validate_accepts_default_opencode_timeout(tmp_path: Path) -> None:
    """DOD-03 / REQ-01: the shipped default emits no message."""

    config = MaintainerConfig(**_valid_path_kwargs(tmp_path))
    assert config.opencode_timeout_sec == 600
    assert config.validate() == []


def test_validate_accepts_valid_opencode_timeouts(
    tmp_path: Path, empty_must_config: None
) -> None:
    """DOD-04 / DOD-07 / REQ-02: in-range values pass; the env layer yields an int."""

    kwargs = _valid_path_kwargs(tmp_path)
    for value in (60, 120, 300, 3600):
        assert MaintainerConfig(**kwargs, opencode_timeout_sec=value).validate() == []

    loaded = MaintainerConfig.load(
        maintainer_root=tmp_path, env={"MAINTAINER_OPENCODE_TIMEOUT_SEC": "120"}
    )
    assert loaded.opencode_timeout_sec == 120
    assert isinstance(loaded.opencode_timeout_sec, int)

    with pytest.raises(ValueError, match="must be an integer"):
        MaintainerConfig.load(
            maintainer_root=tmp_path,
            env={"MAINTAINER_OPENCODE_TIMEOUT_SEC": "ten minutes"},
        )
