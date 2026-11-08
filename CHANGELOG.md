# Changelog

## [v0.57.0] - 2026-11-08

- Add `POST /api/v1/utils/json/to-ndjson`: array -> NDJSON, one compact value per line, single `\n` join, no trailing newline, `lineCount` + UTF-8 `sizeBytes`.\n- Add `POST /api/v1/utils/json/from-ndjson`: NDJSON -> array, blank-line/CRLF tolerant, order preserved, 1-based physical line-number errors, 10000-record bound, empty document -> `[]`.\n- Journal both conversions as deterministic `JSON_TO_NDJSON` / `JSON_FROM_NDJSON` operations with journal replay support.\n- Document both routes in the README endpoint table and JSON utility description.

