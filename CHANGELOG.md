# Changelog

## [v0.131.0] - 2026-11-21

- `POST /api/v1/utils/text/truncate`: `wordBoundary` is now a required field (`@NotNull(message = "wordBoundary is required")`); a null or omitted value returns `400` with `success=false`, `error=VALIDATION_ERROR` and `details=["wordBoundary is required"]` (previously silently treated as `false`/hard cut — breaking change).
- DTO javadoc and the `/truncate` `@Operation` description no longer advertise the field as optional or defaulted.
- `TextService.truncate` keeps its `Boolean.TRUE.equals(...)` null tolerance, so direct service callers and replay of pre-change `TEXT_TRUNCATE` journal entries still hard-cut without an NPE.

