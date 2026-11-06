# Changelog

## [v0.26.0] - 2026-11-06

- **JSON Patch cap:** `POST /api/v1/utils/json/patch` now accepts at most **100** operations. `JsonPatchRequest.operations` carries `@Size(max = 100, message = "operations must contain at most 100 entries")` alongside the retained `@NotEmpty`; oversized lists fail bean validation before `JsonService.patch` runs and return **HTTP 400 `VALIDATION_ERROR`** with no `resultJson` and no patch audit. Applies to `dryRun` too.
- **Tests:** 5 new cases (`JsonControllerTest` 101-op apply, 101-op dry-run, exactly-100 accept; `JsonServiceTest` `@NotEmpty` preservation, bean-validation cap) — full suite 243 tests green.
- **Docs:** README §D documents the cap, the 400 envelope and an oversized-request example.

