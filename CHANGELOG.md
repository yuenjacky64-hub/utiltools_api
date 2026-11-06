# Changelog

## [v0.18.0] - 2026-11-06

- Batch hash jobs: an idempotency key now identifies exactly one canonical request payload (algorithm + ordered items + secretKey fingerprint); reusing a key with a changed payload returns 409 IDEMPOTENCY_CONFLICT instead of the cached job
- Conflict is decided inside the job registry critical section, before registration, so a rejected submission leaves no job row, no idempotency mapping and never hashes its items
- Identical retries, queue-full rollback and post-eviction resubmission behaviour unchanged
- README: documented 409 IDEMPOTENCY_CONFLICT in the Batch Hash Jobs section and the error-code table

