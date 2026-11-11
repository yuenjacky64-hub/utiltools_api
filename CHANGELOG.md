# Changelog

## [v0.104.0] - 2026-11-11

- New POST /api/v1/utils/text/truncate: code-point-aware truncation with optional word-boundary cutting and configurable ellipsis, wired through the audited-operation journal with deterministic TEXT_TRUNCATE replay.
- Two distinct 400 paths: VALIDATION_ERROR (blank text / maxLength < 1) and BAD_REQUEST (maxLength <= ellipsis length, journalled FAILED).

