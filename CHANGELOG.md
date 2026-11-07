# Changelog

## [v0.32.0] - 2026-11-07

- Hash requests rejected at the boundary no longer write a `CRYPTO_HASH` FAILED audit row; service-layer aliases remain intact for direct callers and batch jobs.

