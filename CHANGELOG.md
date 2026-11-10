# Changelog

## [v0.93.0] - 2026-11-10

- Add Jakarta `@Pattern` validation to `TextTransformRequest.operation`, enumerating `UPPER, LOWER, TITLE, CAMEL, SNAKE, KEBAB, SLUG, SENTENCE_CASE` case-insensitively
- `POST /api/v1/utils/text/transform` now rejects an unsupported or whitespace-padded `operation` at the bean-validation boundary with HTTP `400`, `error: VALIDATION_ERROR`, `message: Validation failed` and the supported-operations list in `details` (previously `400 BAD_REQUEST` from a late runtime exception)
- `TextService`/`TextController` unchanged; the `default ->` throw stays as defence in depth for direct callers
- README: transform section and the test-coverage ledger now document the `VALIDATION_ERROR` envelope

