# Changelog

## [v0.17.0] - 2026-11-06

- Add `DELETE /api/v1/utils/jobs/{jobId}` to withdraw a `QUEUED` batch hash job (idempotent `200`, `CANCELLED` snapshot with `completedAt`).
- Add terminal `JobStatus.CANCELLED` and document the lifecycle `QUEUED -> RUNNING -> COMPLETED|FAILED` plus `QUEUED -> CANCELLED`.
- Map `JobCancellationConflictException` to `409 JOB_CANCELLATION_CONFLICT` with `details.jobId`/`details.status` for `RUNNING`/`COMPLETED`/`FAILED` jobs; unknown ids keep `404 JOB_NOT_FOUND`.
- Guarantee a cancelled queued job never invokes `CryptoService.hash`, and evict `CANCELLED` jobs only after `utiltools.jobs.retention-seconds`.

