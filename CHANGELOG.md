# Changelog

## [v0.105.0] - 2026-11-11

- Contact submissions: automatic background retry of FAILED attempts with capped exponential backoff (utiltools.contact.retry-* config; due-scan scheduler).
- FAILED attempts below budget expose nextRetryAt in the submission snapshot; DELIVERED / at-budget / terminal-cancelled attempts do not.
- Manual retry and automatic scans coordinate under registryLock (no double dispatch); saturated-pool/rejection paths restore the row exactly and advance the backoff slot.
- Test-only v2 hardening: deterministic pool-saturation guards in ContactSubmissionServiceTest (awaitCondition, 5 s budget).

