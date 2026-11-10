# Changelog

## [v0.97.0] - 2026-11-10

- Added `@Size(max = 1000000)` to `TextTransformRequest.text`, so `POST /api/v1/utils/text/transform` rejects an oversized body at the DTO boundary with `400 VALIDATION_ERROR` before `TextService` is entered (REQ-01).
- Updated the `text` javadoc bound to `at most 1000000 characters` so the documented contract equals the enforced one; the record keeps compiling and validating under Jakarta Bean Validation (REQ-02).
- Added `TextTransformRequestValidationTest#shouldRejectTextLongerThanOneMillionCharacters`: 1000001 chars -> one `text` violation, 1000000 -> none, `null` -> `text is required` only.

