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
    assert defaults == MaintainerConfig()


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
