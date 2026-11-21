# Changelog

## [v0.140.0] - 2026-11-21

- Add `POST /api/v1/utils/text/diff` (`TextDiffRequest`/`TextDiffResponse`) reporting added/removed/changed tokens with code-point-accurate positions.
- Journal the operation as `TEXT_DIFF` and replay it deterministically through `AuditedOperations.replayInvoke()` (`VERIFIED`, `results match`).
- Bound `left`/`right` with `@NotBlank` + `@Max(2000)` code points; oversized payloads journal `FAILED`/`BAD_REQUEST`.

