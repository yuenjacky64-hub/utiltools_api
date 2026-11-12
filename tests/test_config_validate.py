"""Tests for ``MaintainerConfig.validate`` (UTILTOOLS-75).

``validate()`` is a pure read over ``self``: it never consults the environment
or reloads ``config.json``, and it never raises. Every test therefore builds the
five path fields explicitly (INV-4) so an ambient repo-root ``config.json`` or a
developer's real environment can never change the outcome.
"""

from __future__ import annotations

import dataclasses
import json
import os
from pathlib import Path

import pytest

import ai_maintainer.config as config_module
from ai_maintainer.config import (
    CONFIG_PATH_FIELDS,
    OPTIONAL_CONFIG_PATH_FIELDS,
    REQUIRED_CONFIG_PATH_FIELDS,
    MaintainerConfig,
)


@pytest.fixture()
def empty_must_config(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config_module, "_load_must_config", lambda: {})


def _make_dirs(root: Path) -> dict[str, Path]:
    """Create the five directories under ``root`` and return them by field name."""

    created: dict[str, Path] = {}
    for name in CONFIG_PATH_FIELDS:
        path = root / name
        path.mkdir(parents=True, exist_ok=True)
        created[name] = path
    return created


def _all_valid_kwargs(root: Path) -> dict[str, str]:
    return {name: str(path) for name, path in _make_dirs(root).items()}


# --- DOD-01 / REQ-01 -------------------------------------------------------


def test_validate_reports_missing_required_root() -> None:
    config = MaintainerConfig()

    issues = config.validate()

    assert isinstance(issues, list)
    assert issues == [
        "maintainer_root: not configured",
        "ai_team_root: not configured",
        "default_project_root: not configured",
    ]
    # optional dirs are never reported when unset
    assert not any(name in issue for issue in issues for name in OPTIONAL_CONFIG_PATH_FIELDS)


# --- DOD-02 / REQ-01, REQ-02 ----------------------------------------------


def test_validate_returns_empty_list_when_all_paths_valid(tmp_path: Path) -> None:
    config = MaintainerConfig(**_all_valid_kwargs(tmp_path))

    assert config.validate() == []


# --- DOD-03 / REQ-02 -------------------------------------------------------


_IS_ROOT = hasattr(os, "geteuid") and os.geteuid() == 0


def test_validate_reports_inaccessible_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    kwargs = _all_valid_kwargs(tmp_path)
    locked = Path(kwargs["data_dir"])
    locked.chmod(0o000)
    try:
        if _IS_ROOT:
            # root bypasses mode bits, so the ``not accessible`` rule is
            # exercised through ``os.access`` instead of a real chmod.
            real_access = config_module.os.access
            monkeypatch.setattr(
                config_module.os,
                "access",
                lambda path, mode: False if Path(path) == locked else real_access(path, mode),
            )
        issues = MaintainerConfig(**kwargs).validate()
    finally:
        locked.chmod(0o700)

    assert issues == [f"data_dir: not accessible: {locked}"]


# --- DOD-04 / REQ-02 -------------------------------------------------------


def test_validate_skips_unset_optional_dirs(tmp_path: Path) -> None:
    dirs = _make_dirs(tmp_path)
    config = MaintainerConfig(
        maintainer_root=str(dirs["maintainer_root"]),
        ai_team_root=str(dirs["ai_team_root"]),
        default_project_root=str(dirs["default_project_root"]),
    )

    issues = config.validate()

    assert issues == []
    assert not any(name in issue for issue in issues for name in OPTIONAL_CONFIG_PATH_FIELDS)


# --- DOD-05 / REQ-01 -------------------------------------------------------


def test_validate_reports_not_a_directory(tmp_path: Path) -> None:
    kwargs = _all_valid_kwargs(tmp_path)
    plain_file = Path(kwargs["ai_team_root"]) / "sibling-file"
    plain_file.write_text("not a directory", encoding="utf-8")
    kwargs["ai_team_root"] = str(plain_file)

    issues = MaintainerConfig(**kwargs).validate()

    assert issues == [f"ai_team_root: not a directory: {plain_file}"]


# --- DOD-06 / REQ-01, REQ-02 ----------------------------------------------


def test_load_then_validate_returns_list_without_raising(
    tmp_path: Path, empty_must_config: None
) -> None:
    config = MaintainerConfig.load(maintainer_root=tmp_path, env={})

    issues = config.validate()

    assert isinstance(issues, list)
    assert issues == [
        "ai_team_root: not configured",
        "default_project_root: not configured",
    ]


# --- DOD-07 / REQ-01, REQ-02 ----------------------------------------------


def test_path_fields_exist_with_self_attribute_access(
    tmp_path: Path, empty_must_config: None
) -> None:
    dataclass_fields = {field.name for field in dataclasses.fields(MaintainerConfig)}
    defaults = MaintainerConfig()
    for name in CONFIG_PATH_FIELDS:
        assert name in dataclass_fields
        assert getattr(defaults, name) is None

    loaded = MaintainerConfig.load(maintainer_root=tmp_path, env={})

    # ``load()`` records the resolved root as the weakest layer (INV-5), which
    # is what lets ``validate()`` check the root the caller selected; the other
    # four knobs stay ``None`` so "unset" stays distinguishable from "broken".
    assert loaded.maintainer_root == str(tmp_path)
    assert loaded.ai_team_root is None
    assert loaded.default_project_root is None
    assert loaded.data_dir is None
    assert loaded.reports_dir is None
    assert issubclass(type(loaded), MaintainerConfig)
    assert REQUIRED_CONFIG_PATH_FIELDS == (
        "maintainer_root",
        "ai_team_root",
        "default_project_root",
    )
    assert OPTIONAL_CONFIG_PATH_FIELDS == ("data_dir", "reports_dir")


# --- DOD-08 / REQ-01, REQ-02 ----------------------------------------------


def test_config_json_accepts_path_keys(tmp_path: Path, empty_must_config: None) -> None:
    dirs = _make_dirs(tmp_path / "roots")
    payload = {name: str(path) for name, path in dirs.items()}
    (tmp_path / "config.json").write_text(json.dumps(payload), encoding="utf-8")

    config = MaintainerConfig.load(maintainer_root=tmp_path, env={})

    for name in CONFIG_PATH_FIELDS:
        assert getattr(config, name) == payload[name]
    assert config.validate() == []


# --- DOD-09 / REQ-01 -------------------------------------------------------


def test_load_records_resolved_root_as_weakest_layer(
    tmp_path: Path, empty_must_config: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("MAINTAINER_MAINTAINER_ROOT", str(tmp_path / "env-root"))
    config = MaintainerConfig.load(maintainer_root=tmp_path, env=dict(os.environ))

    # env beats the fallback layer (INV-5)
    assert config.maintainer_root == str(tmp_path / "env-root")

    monkeypatch.delenv("MAINTAINER_MAINTAINER_ROOT")
    assert (
        MaintainerConfig.load(maintainer_root=tmp_path, env={}).maintainer_root
        == str(tmp_path)
    )


# --- DOD-10 / REQ-01, REQ-02 ----------------------------------------------


def test_validate_never_raises_on_hostile_paths() -> None:
    for raw in (
        "~utiltools75-no-such-user/x",
        "/tmp/\x00evil",
        "/" + "a" * 5000,
        "   ",
        "relative/path",
    ):
        issues = MaintainerConfig(maintainer_root=raw).validate()
        assert isinstance(issues, list)
        assert all(isinstance(item, str) for item in issues)

    assert "path could not be expanded: " in MaintainerConfig(
        maintainer_root="~utiltools75-no-such-user/x"
    ).validate()[0]


# --- UTILTOOLS-112: email ---------------------------------------------------


def test_validate_flags_malformed_email(tmp_path: Path) -> None:
    """REQ-02: a non-empty email failing the format check yields exactly one issue."""

    kwargs = _all_valid_kwargs(tmp_path)
    for value in ("not-an-email", "a@b", "@b.co", "a b@c.co"):
        issues = MaintainerConfig(**kwargs, email=value).validate()
        assert issues == [f"email: invalid format: {value}"]


def test_validate_accepts_well_formed_email_and_default(tmp_path: Path) -> None:
    """REQ-02 / REQ-03: a valid email or the empty default emits no email issue."""

    kwargs = _all_valid_kwargs(tmp_path)
    for value in ("a@b.co", "ops@example.com", "first.last@sub.domain.org"):
        assert MaintainerConfig(**kwargs, email=value).validate() == []
    assert MaintainerConfig(**kwargs).validate() == []


def test_validate_docstring_documents_email_rule() -> None:
    """DOD-03: the docstring message list stays in lockstep with the emitted issue."""

    doc = MaintainerConfig.validate.__doc__ or ""
    assert "email: invalid format: <email>" in doc
