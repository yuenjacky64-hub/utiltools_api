# Changelog

## [v0.66.0] - 2026-11-08

- Add `POST /api/v1/utils/json/csv-to-json` converting RFC 4180 CSV text into a JSON array of objects with optional `delimiter` and `headerMapping` header renames; journalled as `JSON_CSV_TO_JSON` and replayable; malformed CSV returns 400.

