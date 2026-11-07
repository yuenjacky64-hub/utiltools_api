# Changelog

## [v0.36.0] - 2026-11-07

- POST /api/v1/utils/json/format accepts an optional sortKeys flag that recursively sorts object field names (ascending String.compareTo) before serialization; array element order and scalar values are preserved.
- Composable with minify and indent; sizeBytes is measured on the final sorted output; existing payloads default to insertion-order behaviour.
- OpenAPI description and README guide document the flag with an example; JSON_FORMAT journaling/replay unchanged.

