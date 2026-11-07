# Changelog

## [v0.44.0] - 2026-11-07

- Batch hash jobs checkpoint every completed item: `completedItems` (additive, `0..itemCount`) is exposed on the job snapshot and a retried job resumes at the first unhashed index instead of rehashing from item 0.
- Fencing: only the live attempt generation may commit a checkpoint item; a stale, reaped or cancelled worker appends nothing and leaves `results`, `error` and `status` untouched.
- `retry()` preserves the checkpoint; a `429 QUEUE_FULL` retry restores the exact pre-retry `FAILED` snapshot (attempt, `completedItems`, checkpoint) without consuming an attempt.
- `JobAttempt.resumedFrom` plus `resumedFrom`/`completedItems` in the `BATCH_HASH` audit descriptor and outcome keep attempt history and audit in agreement; items and `secretKey` are never journaled. Cancellation, eviction, retention and idempotency semantics are unchanged.

