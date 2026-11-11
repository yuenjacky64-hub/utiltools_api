# Changelog

## [v0.107.0] - 2026-11-11

- New POST /api/v1/utils/text/word-frequency: totalWords, distinctWords and a bounded, deterministically ordered top-N word list (count desc, word asc)
- Optional limit (1..100, default 10), caseSensitive (default false) and ignoreWords (max 1000 x 100 chars) with Locale.ROOT folding
- Journalled as TEXT_WORD_FREQUENCY; replay re-runs the stored request and VERIFIES identical output
- README route table, request/response example and journalled-operations list updated

