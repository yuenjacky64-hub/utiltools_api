# Changelog

## [v0.42.0] - 2026-11-07

- Add `SENTENCE_CASE` operation to `POST /api/v1/utils/text/transform` (first letter of each `[.!?]`-delimited sentence upper-cased, remaining letters lower-cased; `Locale.ROOT` only, replay-safe)
- Extend `ALLOWED_OPERATIONS`, DTO javadoc, `@Operation` description and README operation lists to the eight-value enumeration
- Tests: 3 new `TextServiceTest` sentence-case cases + `TextControllerTest#shouldSentenceCaseAndEchoOperation`; stabilise the pre-existing `BatchHashJobServiceTest` quota-counter race (test-only)

