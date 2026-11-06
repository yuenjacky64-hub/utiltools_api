# Changelog

## [v0.23.0] - 2026-11-06

- Add POST /api/v1/utils/jobs/{jobId}/retry: atomic FAILED -> QUEUED requeue with 202 Accepted, 409 JOB_RETRY_CONFLICT, 404 JOB_NOT_FOUND and 429 QUEUE_FULL.
- Fence workers by attempt generation so a stale worker from a superseded attempt cannot write RUNNING/COMPLETED/FAILED.
- Expose attempt, maxAttempts and a bounded per-attempt history on the job snapshot; add utiltools.jobs.max-attempts (default 3).
- Restore the FAILED snapshot on queue rejection so a rejected requeue does not consume an attempt.

