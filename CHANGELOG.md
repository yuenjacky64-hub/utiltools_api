# Changelog

## [v0.132.0] - 2026-11-21

- README `## ⚡ API Endpoints Reference` table now documents all 67 shipped handler mappings (11 previously undocumented routes added: `GET /datetime/week`, `POST /json/flatten`, `POST /json/unflatten`, `POST /json/to-csv`, `POST /json/from-csv`, `POST /text/truncate`, `POST /audit/reaps`, `GET /audit/quarantine-status`, `GET /resilience`, `POST /resilience/probe`, `GET /rate-limit/status`).
- New read-only probe `tools/check_readme_route_coverage.sh` re-derives the shipped route set from controller annotations and the README table set on every run; `--routes`/`--unknown`/`--test-counts`/`--self-test`/`--all`, exit 0 clean / 1 drift / 2 usage. Drift can no longer return silently.
- README status block test counts re-derived instead of hand-typed: badge `Tests-41` -> `Tests-939` and prose `48 unit and integration tests` -> `939 unit and integration tests` (matches counted `@Test` methods).

