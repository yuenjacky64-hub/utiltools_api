# Changelog

## [v0.136.0] - 2026-11-21

- Add `TOGGLE_CASE` operation to `POST /api/v1/utils/text/transform` (inverts each cased letter; digits, punctuation and symbols pass through).\n- Update `TextTransformRequest` validation whitelist/message, `TextService` switch + `ALLOWED_OPERATIONS`, Swagger description and README operation tables.\n- Cover mixed-case/all-caps/all-lowercase, digits/symbols/emoji, title-case and validation near-misses in the three existing text test suites.

