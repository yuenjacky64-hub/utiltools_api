# Changelog

## [v0.55.0] - 2026-11-08

- Batch hash jobs accept an optional `deadlineSeconds` (1..3600) plus an operator default `utiltools.jobs.default-deadline-seconds` (0 disables); a job past its deadline fails with `JOB_DEADLINE_EXCEEDED`, keeps its committed checkpoint prefix for retry, and releases client quota exactly once.

