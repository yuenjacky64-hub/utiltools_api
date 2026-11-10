# Changelog

## [v0.83.0] - 2026-11-10

- `MemoryStore._load()` now requires `runs`, `bug_fixes` and `learnings` to exist and be lists (REQ-01/02/03).
- A structurally invalid `maintainer_memory.json` is renamed to `maintainer_memory.json.corrupt-<stamp>` (mode 0o600) via `_quarantine_corrupt_file()`, and the store loads empty.
- New `.gitignore` rule for quarantine siblings.

