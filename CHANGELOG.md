# Changelog

## [v0.74.0] - 2026-11-09

- **NEW** `src/ai_maintainer/consistency.py` gains five pure validators — `validate_run_integrity`, `validate_bug_fix_integrity`, `validate_learning_dedup`, `validate_memory_document`, `validate_memory_records` — returning `list[str]` of `CODE: detail` lines from the new `ERROR_CODES` vocabulary (REQ-01).
- **NEW** `ai_maintainer.memory.MemoryStore` persists `maintainer_memory.json` atomically (`.tmp` sibling + `os.replace`, symlink refused) and runs the validators in `_save()`, logging one `WARNING` per issue while still writing the document (REQ-02).
- `MaintainerEngine` accepts an injected `memory=` store (plus `install_memory()`), calls `_finish_cycle()` from `run_cycle()` after the record bookkeeping and before the `CycleResult` report, and appends a `CONSISTENCY` cycle event that can never fail a cycle (REQ-03).
- **NEW** `tests/test_consistency.py`: 49 cases covering empty/non-sequence input, duplicate ids, normalised-text learning duplication, orphaned bug fixes, corrupt envelopes, atomic/symlink-safe saves and the engine seam (REQ-04).

