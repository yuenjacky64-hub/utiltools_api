# Changelog

## [v0.4.0] - 2026-11-01

- Add bounded in-memory operation journal (`utiltools.audit.max-entries`, default 200, oldest-first eviction) correlating every `/api/v1/utils/*` call with an `X-Request-Id`.
- Add `RequestIdFilter` (`OncePerRequestFilter` on `/api/v1/**`) + `RequestContext`: accepts `^[A-Za-z0-9_-]{8,64}$` or generates a UUID, echoes it on the response, clears the ThreadLocal in `finally`.
- Add `requestId` to `ApiResponse` (`success`/`error`) without changing existing `timestamp` semantics or `GlobalExceptionHandler` mappings.
- Add `audit` model package (`OperationType`, `JournalState`, `ReplayState`, `AuditEntry`, `ReplayRecord`) with DATETIME_CURRENT and CRYPTO_UUID marked non-deterministic.
- Add `AuditJournalService` (PENDING -> SUCCEEDED/FAILED only, `IllegalStateException` on duplicate/late transitions, staged result cleared on fail) and `AuditedOperations` wired into all nine DateTime/Crypto/Json operations.
- Add `AuditReplayService` + `AuditController`: `GET /api/v1/utils/audit/entries?limit=&state=`, `GET /api/v1/utils/audit/entries/{id}`, `POST /api/v1/utils/audit/entries/{id}/replay`; replay re-invokes the stored request, compares `valueToTree` output and records VERIFIED/MISMATCH without mutating the original entry.
- Add `AuditEntryNotFoundException` (404 `NOT_FOUND`) and `ReplayConflictException` (409 `REPLAY_CONFLICT`) for unknown ids, FAILED entries, non-deterministic operations and oversized non-replayable payloads; secret fields (`secretKey`) redacted from every audit response.
- Add `AuditJournalServiceTest`, `AuditReplayServiceTest`, `RequestIdFilterTest`, `AuditControllerTest` plus `requestId` assertions in the shared table-driven controller tests; document the audit surface in `README.md`.
