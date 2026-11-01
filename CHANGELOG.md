# Changelog

## [v0.14.0] - 2026-11-01

### Added
- `POST /api/v1/utils/json/patch` applies an ordered RFC 6902-style operation list (`add`, `remove`, `replace`, `test`, `move`, `copy`) to a deep copy of the submitted document.
- Ordered per-operation audit trail (`PENDING` → `APPLIED` / `FAILED` → `ROLLED_BACK` / `VALIDATED`) returned in every response.
- `JsonPatchRollbackException` + `GlobalExceptionHandler` mapping to HTTP 422 `error=PATCH_ROLLBACK` with the full audit in `details.audit`.
- `dryRun=true` validation mode that applies the list but returns `resultJson: null`.
- `README.md` endpoint table row and JSON section curl example documenting rollback semantics.

### Fixed
- Feature branch rebased onto `main` (merge of `origin/main` `c983f18`) so the pull request no longer conflicts with the base branch.

