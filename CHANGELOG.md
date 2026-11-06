# Changelog

## [v0.24.0] - 2026-11-06

- Batch hash jobs: idempotency keys are now bound to the canonical request payload; reusing a key with a changed `algorithm`, `items` or `secretKey` returns `409 IDEMPOTENCY_CONFLICT` instead of the cached job snapshot (README + error-code table updated)
- Merge of `origin/main` (`94c51f4`) into `ai-team/UTILTOOLS-17` to clear the PR #13 conflict: conflict resolutions keep both sides' behaviour (idempotency-conflict guard **and** queued-job cancellation, `IDEMPOTENCY_CONFLICT` **and** `JOB_CANCELLATION_CONFLICT` mappings, union of both test suites)

