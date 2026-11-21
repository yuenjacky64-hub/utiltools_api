# Changelog

## [v0.138.0] - 2026-11-21

- `TextSimilarityRequest.pattern` now carries `@Size(max = 512, message = "pattern must be at most 512 characters")`, so an over-long pattern answers `400 VALIDATION_ERROR` regardless of `tokenizer` (and is not journalled).
- New optional `timeoutMs` field (`@Min(0)`, `@Max(60000)`, default/null = no per-request timeout) lets a `regex` similarity call tighten its wall-clock budget; `TextService.similarity()` threads `min(timeoutMs, utiltools.text.regex-timeout-ms)` into `BudgetedCharSequence`, so an exhausted budget answers `422 REGEX_TIMEOUT` with `details.timeoutMs` set to the budget that actually applied.
- Journalled `TEXT_SIMILARITY` entries stay replay-compatible: replay bypasses bean validation and `null`/`0` reproduces the pre-change path byte-for-byte.

