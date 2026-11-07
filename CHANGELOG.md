# Changelog

## [v0.41.0] - 2026-11-07

- Batch hash jobs now checkpoint every completed item: `completedItems` is exposed additively on the job snapshot and a retried job resumes at the first unhashed index instead of rehashing from item 0.
- Fencing: only the live attempt generation may commit a checkpoint item; stale, reaped or cancelled workers write nothing.
- `retry()` preserves the checkpoint; a `429 QUEUE_FULL` retry restores the exact pre-retry `FAILED` snapshot without consuming an attempt.
- `JobAttempt.resumedFrom` plus `resumedFrom`/`completedItems` in the `BATCH_HASH` audit descriptor and outcome make attempt history and audit agree. No items or `secretKey` are ever journaled.

