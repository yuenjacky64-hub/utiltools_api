# Changelog

## [v0.92.0] - 2026-11-10

- `BatchHashJobService` now isolates per-item hash failures: an item whose `CryptoService.hash` throws is recorded `FAILED` (content-free, code `BATCH_ITEM_HASH_FAILED`) and the worker continues with the remaining items instead of failing the whole batch.
- Job snapshots and every `attempts[]` row now always serialize additive `succeededItems` / `failedItems` index lists; `completedItems` is the durable success count (`checkpoint.size()`), not a contiguous leading prefix.
- `POST /api/v1/utils/jobs/{id}/retry` resumes at the first unprocessed index, skipping both succeeded and failed indices, so already-hashed progress survives a partial failure.
- The per-attempt `BATCH_HASH` audit entry carries `itemSuccesses`, `itemFailures`, `resumedFrom` and `checkpointItems`; new additive `AuditJournalService.fail(id, code, result)` overload stores a bounded payload on `PENDING -> FAILED`.
- Execution lease/heartbeat are renewed per hashed item, including after an isolated item failure; reaper behaviour unchanged. README documents the partial-result contract.

