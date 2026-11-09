# Changelog

## [v0.67.0] - 2026-11-09

- Batch hash jobs: per-client FIFO lanes + round-barrier round-robin dispatcher (bounded starvation: no client gets a second dispatch before every other queued client gets one)
- Queue-full rollback, per-client quotas, idempotency, cancellation, retry, deadline and retention semantics unchanged; rejected idempotency keys remain reusable
- New package-private queuedJobsForClient accessor plus README/Javadoc documentation of the fairness rule, starvation bound and unchanged queue-full contract

