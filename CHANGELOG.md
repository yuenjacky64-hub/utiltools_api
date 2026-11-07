# Changelog

## [v0.48.0] - 2026-11-07

### Added
- `MaintainerConfig.load()` auto-detects `config.json` at the maintainer root when no explicit `config_path` is given (REQ-01).
- `load_config_file()`, `is_free_model()` and the `MAINTAINER_*` env-var layer in `src/ai_maintainer/config.py`.

### Changed
- Configuration precedence is now CLI overrides > env vars > `config.json` > project must-config module > built-in defaults (REQ-02).

