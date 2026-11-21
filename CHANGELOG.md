# Changelog

## [v0.135.0] - 2026-11-21

- `POST /api/v1/utils/text/similarity` accepts an optional `ignoreWords` list; entries are removed from both `left` and `right` before the Levenshtein distance is measured (REQ-01, REQ-02).
- Matching is `Locale.ROOT`-folded when `caseSensitive` is false (default) and exact when it is `true`; absent, `null` or empty lists keep the pre-existing response byte-for-byte.
- Re-cut of the superseded `v0.134.0` release commit (`1946ce6`, never merged upstream) on top of `origin/main` `8248323`, clearing PR #123's base-branch conflict.

