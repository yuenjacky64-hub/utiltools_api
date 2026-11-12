# Changelog

## [v0.115.0] - 2026-11-12

- New POST /api/v1/utils/text/normalize: compose/decompose Unicode text via java.text.Normalizer (NFC, NFD, NFKC, NFKD), echoing upper-cased form, changed flag and code-point length inside the ApiResponse envelope.- Oversized normalized results (> 1,000,000 chars) are rejected with 400 BAD_REQUEST before the envelope/journal; blank/unknown forms return 400 VALIDATION_ERROR.- Journalled as TEXT_NORMALIZE (deterministic); audit replay reports VERIFIED without appending a second entry.- README endpoint table and Text section updated; full suite 840/840 green.

