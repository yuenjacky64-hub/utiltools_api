# Changelog

## [v0.80.0] - 2026-11-09

- `MemoryStore.export_memory(path=None)` returns the version-1 memory envelope (runs/bug_fixes/learnings), newest-last, and writes atomically via `atomic_write_text` when a path is given.
- `MemoryStore.import_memory(source)` classmethod accepts a dict or path, merges without duplicating (task_id / git_commit / learning timestamp), prunes to config limits, refuses to persist an invalid document, and returns `list[str]` of `MEMORY_IMPORT_*` messages.
- New `cli.py memory` subcommand with mutually-exclusive, required `--export-memory PATH` / `--import-memory PATH`; validates paths and surfaces `error:` on stderr with exit 1.
- New `tests/test_memory.py`: 16 DoD tests (export, atomic write, ordering, dedupe, rollback, pruning, idempotency, CLI round-trip and path validation).

