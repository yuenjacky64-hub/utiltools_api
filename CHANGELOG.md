# Changelog

## [v0.21.0] - 2026-11-06

- New `/api/v1/utils/text` module: `POST /transform` (UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG) and `POST /stats` (code-point accurate characters, charactersNoWhitespace, words, lines, sentences).
- `TEXT_TRANSFORM` / `TEXT_STATS` journalled through `AuditedOperations` and replayable as `VERIFIED` via `POST /api/v1/utils/audit/entries/{id}/replay`.
- README + OpenAPI documentation; no new runtime dependency.

