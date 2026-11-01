# Changelog

## [v0.12.0] - 2026-11-01

- Add `X-Request-Id` correlation (`RequestIdFilter`, `RequestContext`) echoed on every response and on the `requestId` envelope field (REQ-01, REQ-02).
- Add bounded in-memory audit journal for all nine `/api/v1/utils/*` operations with `PENDING -> SUCCEEDED|FAILED` transitions, secret redaction, oversized-payload withholding and oldest-first eviction at `utiltools.audit.max-entries=200` (REQ-03..REQ-05).
- Add `GET /api/v1/utils/audit/entries`, `GET /api/v1/utils/audit/entries/{id}` and `POST /api/v1/utils/audit/entries/{id}/replay` with deterministic replay verification (REQ-06).
- Add 404 `NOT_FOUND` and 409 `REPLAY_CONFLICT` audit error mappings without touching existing framework handler mappings (REQ-07).

