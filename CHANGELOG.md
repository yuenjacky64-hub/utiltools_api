# Changelog

## [v0.50.0] - 2026-11-08

- Late workers and repeated drains are fenced by the existing attempt-generation/status check, so a returning worker cannot overwrite the `FAILED` snapshot, append to the checkpoint, publish results or close the audit entry twice.

