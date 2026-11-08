# Changelog

## [v0.63.0] - 2026-11-08

- `ai-maintainer status --json` emits one strictly valid JSON object (daemon/orchestrator state, active task, memory, queue, counters); text mode renders from the same payload.
- Hardened `maintainer_status.json` reads: non-regular documents, deeply nested JSON, non-finite numbers and control characters degrade to `null`/`unavailable` instead of raising or forging output.

