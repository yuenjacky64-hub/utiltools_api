# Changelog

## [v0.90.0] - 2026-11-10

### Added
- `MaintainerConfig.opencode_timeout_sec` (default `600`, constant `DEFAULT_OPENCODE_TIMEOUT_SEC`) with a `validate()` range rule for the inclusive `[60, 3600]` second window (UTILTOOLS-90 REQ-01, REQ-02).
- `validate()` reports `opencode_timeout_sec must be a positive integer` for non-`int`/`bool`/`<= 0` values, `... must be >= 60` below the floor and `... must be <= 3600` above the ceiling; at most one message, appended after the path-field loop.
- Env/keyword coercion: `MAINTAINER_OPENCODE_TIMEOUT_SEC=120` loads as `int` 120.
- Five new tests in `tests/test_config.py` (append-only; no existing assertion changed).
- `.gitignore` now ignores `__pycache__/`, `*.pyc`, `*.pyo`, `*.pyd` (compliance-gate hygiene).

