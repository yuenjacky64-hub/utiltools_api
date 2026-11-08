# Changelog

## [v0.58.0] - 2026-11-08

- Add optional `deadlineSeconds` (1..3600) to `BatchHashRequest` and the `utiltools.jobs.default-deadline-seconds` operator property (0 disables the default).
- Persist `deadlineAt = createdAt + effective deadline`, include it in the canonical idempotency fingerprint, and expose it on `BatchHashJobResponse` (GET `/api/v1/utils/jobs/{jobId}` and `/api/v1/utils/jobs`).
- Fail deadline-lapsed jobs with the distinct `JOB_DEADLINE_EXCEEDED` error from both the worker loop and the scheduled reaper (QUEUED never admitted, RUNNING with a live lease), keeping the committed checkpoint prefix for a later retry.
- Let a CANCELLING job past its deadline terminalize as `CANCELLED`; every reaper pass stays idempotent.
- Retry of a deadline failure starts attempt+1 with a fresh deadline and resumes from the retained checkpoint.

