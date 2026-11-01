# Changelog

## [v0.7.0] - 2026-11-01

- Add `com.example.utiltools.jobs`: `POST /api/v1/utils/jobs/hash` (202 + jobId) and `GET /api/v1/utils/jobs/{jobId}` polling over a bounded `ThreadPoolExecutor`/`ArrayBlockingQueue` with `AbortPolicy`.
- Idempotent submission: one `jobId` per `idempotencyKey` with exactly one execution and input-order-preserving results.
- Strict `QUEUED -> RUNNING -> COMPLETED|FAILED` state machine; worker `Throwable` marks the job `FAILED` without wedging the pool.
- Atomic rollback of job + idempotency mapping on `RejectedExecutionException`, surfaced as HTTP 429 `QUEUE_FULL`; unknown/evicted ids return 404 `JOB_NOT_FOUND`.
- Scheduled eviction of terminal jobs past `utiltools.jobs.retention-seconds` (`@EnableScheduling`), plus new `worker-count`/`queue-capacity`/`cleanup-interval-ms` properties.
- Tests: `BatchHashJobServiceTest` (concurrency dedup, saturation rollback+retry, worker failure, eviction safety) and `BatchJobControllerTest` (202/400/404/429); README endpoint reference and curl examples.

