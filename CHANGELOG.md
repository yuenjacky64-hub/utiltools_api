# Changelog

## [v0.129.0] - 2026-11-21

- Add `POST /api/v1/utils/text/similarity` computing a code-point-aware Levenshtein distance between `left` and `right`, reporting `distance`, `maxLength`, rounded `similarity`, `identical` and the effective `caseSensitive` flag (default `false`, folded with `Locale.ROOT`).
- Bound each input at 2000 code points, rejected as `400 BAD_REQUEST` before matrix allocation so the journalled attempt is `FAILED`.
- Journal and replay the new route as `TEXT_SIMILARITY` (`OperationType.TEXT_SIMILARITY(true)`, `AuditedOperations.similarity` wrapper, exhaustive `replayInvoke` branch).

