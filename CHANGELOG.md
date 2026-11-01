# Changelog

## [v0.5.0] - 2026-11-01

- Guard forced `EPOCH_SECONDS` values whose millisecond form would overflow a `long` (400, never 500); document the field and its allowed values in `README.md` §1.A.

