# Changelog

## [v0.82.0] - 2026-11-09

- `MemoryStore.validate()` reports every integrity problem in `maintainer_memory.json` as a `collection[index]`-tagged error string (`MEMORY_ORDER`, `BUG_FIX_MISSING_FIELD`, `LEARNING_MISSING_FIELD`, `RUN_DUPLICATE_TASK_ID`, `BUG_FIX_DUPLICATE_GIT_COMMIT`, `LEARNING_DUPLICATE`, `MEMORY_OVER_LIMIT`), returning `[]` for a clean document and never mutating or rewriting the file.
- New `cli.py memory --validate-memory` option: prints `memory is valid: runs=… bug_fixes=… learnings=… (<path>)` and exits 0, or one `error:`-headed line per problem on stderr and exits 1. Limits are read from `config.json` when present. Validation stays total on untrusted JSON timestamps (`NaN`, `Infinity`, integers beyond float range) — no traceback escapes.
- 17 new tests in `tests/test_memory.py` pin ordering, missing-field, duplicate, over-limit, corrupt-document, no-mutation and CLI exit-code behaviour.

