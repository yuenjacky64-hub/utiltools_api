# Changelog

## [v0.69.0] - 2026-11-09

- Add `POST /api/v1/utils/json/pointer` resolving a single RFC 6901 JSON Pointer against a JSON document, reusing the shared `parsePointer` tokeniser from JSON Patch.
- Report `pointer`, `found`, `value` and `valueType` (`OBJECT|ARRAY|STRING|NUMBER|BOOLEAN|NULL`); the empty pointer `""` addresses the whole document.
- Reject pointers without a leading `/`, malformed escapes (`~2`, trailing `~`), pointers over 1024 characters or 64 segments, and traversal through a scalar with `400 BAD_REQUEST`.
- Journal the lookup as deterministic `OperationType.JSON_POINTER(true)` via `AuditedOperations.pointer`, so `POST /api/v1/utils/audit/entries/{id}/replay` reproduces the stored result.

