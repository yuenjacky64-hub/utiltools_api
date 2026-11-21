# Changelog

## [v0.134.0] - 2026-11-21

- `POST /api/v1/utils/text/similarity` accepts an optional `ignoreWords` list; listed words are stripped from both `left` and `right` before the Levenshtein distance, with `Locale.ROOT` folding unless `caseSensitive=true` (UTILTOOLS-140)

