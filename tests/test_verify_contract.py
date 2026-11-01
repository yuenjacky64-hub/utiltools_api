"""Guard for this repo's verification contract.

`utiltools_api` is a Maven/Spring Boot project: the authoritative test command
is `bash ./mvnw -q clean test` (Plan §8, DOD-01). The harness compliance gate
auto-detects a language by looking for `tests/`, `pyproject.toml` or
`pytest.ini`; this directory exists only to hold the `*.should` DoD evidence
shims, so the auto-detected `pytest` run used to exit 5 ("no tests ran") and
bounced Developer Done.

This module keeps the auto-detected path green and asserts that the Maven
contract is still the one pinned in `.ait/verify.toml`, so the real Java suite
is what CI and the gate execute. It adds no application behaviour.
"""

from __future__ import annotations

import tomllib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


def test_maven_project_layout_is_intact() -> None:
    assert (REPO_ROOT / "pom.xml").is_file()
    assert (REPO_ROOT / "mvnw").is_file()
    assert (REPO_ROOT / "src" / "main" / "java").is_dir()
    assert (REPO_ROOT / "src" / "test" / "java").is_dir()


def test_verify_contract_pins_maven_test_command() -> None:
    contract = REPO_ROOT / ".ait" / "verify.toml"
    assert contract.is_file(), "missing .ait/verify.toml"
    data = tomllib.loads(contract.read_text(encoding="utf-8"))
    commands = data.get("command") or []
    assert commands, "verify.toml declares no [[command]]"
    test_cmd = next((c for c in commands if c.get("name") == "test"), None)
    assert test_cmd is not None, "verify.toml has no 'test' command"
    assert "mvnw" in str(test_cmd.get("run", "")), (
        "verify contract must run the Maven suite, not pytest: "
        f"{test_cmd.get('run')!r}"
    )
