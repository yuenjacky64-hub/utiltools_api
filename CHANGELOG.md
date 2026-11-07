# Changelog

## [v0.33.0] - 2026-11-07

- `POST /api/v1/utils/text/replace`: deterministic literal/regex find-and-replace with first/all modes, `caseInsensitive`, replacement counts (`replacements`, `changed`, code-point `length`).
- `TextReplaceRequest` / `TextReplaceResponse` DTOs; pattern and replacement bounded with `@Size`, 1,000,000-character result bound enforced per match (no `$n` amplification).
- `OperationType.TEXT_REPLACE` (deterministic) recorded through `AuditedOperations.replace` and replayable via `replayInvoke`; README endpoint table and text utility guide updated.

