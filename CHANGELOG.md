# Changelog

## [v0.39.0] - 2026-11-07

- Add `POST /api/v1/utils/json/merge-patch` applying RFC 7396 merge-patch documents (`null` deletes a member, objects merge recursively, arrays/scalars replace wholesale).
- Journal the endpoint as the new `JSON_MERGE_PATCH` operation with `PENDING -> SUCCEEDED/FAILED` lifecycle and replay support that reports `VERIFIED` without appending a second entry.
- Document the endpoint (README table row, curl example, journal operation list) and add 10 service tests plus 5 controller tests covering nesting, deletion, replacement, immutability, malformed JSON and the 64-level depth fence.

