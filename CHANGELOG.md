# Changelog

## [v0.76.0] - 2026-11-09

- `MaintainerConfig.validate()` returns a `list[str]` of path issues (`not configured`, `path could not be expanded`, `path is not absolute`, `not a directory`, `not accessible`) for the five path fields and `[]` when every path is usable.
- `maintainer_root`, `ai_team_root`, `default_project_root` (required) and `data_dir`, `reports_dir` (optional) are now `MaintainerConfig` path fields, accepted from `config.json` and the `MAINTAINER_*` layers; non-string values raise `<key> must be a path string`.
- `MaintainerConfig.load()` records the resolved root as the weakest layer, so `load().validate()` validates the root actually in use.

