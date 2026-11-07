# Changelog

## [v0.35.0] - 2026-11-07

- Add `POST /api/v1/utils/audit/verify?limit=N` (default 20, clamped to `utiltools.audit.max-entries`) that sweep-verifies the journal newest-first and reports per-entry `VERIFIED` / `MISMATCH` / `FAILED` / `SKIPPED`.
- Share one per-entry attempt helper between `replay(id)` and `verify(limit)` so single-entry and sweep outcomes cannot diverge; `replay(id)` keeps its 200/404/409 contract.
- Isolate failures per entry: live `IN_PROGRESS` claims are `SKIPPED`/`LIVE_CLAIM` without touching their lease, evicted and ineligible entries are `SKIPPED` with a reason, and a throwing replay is `FAILED` without aborting the sweep or rewriting original audit rows.
- Add bounded, payload-free `JournalVerifyResponse` / `JournalVerifyResult` DTOs plus unit and MockMvc coverage, README and OpenAPI documentation.

