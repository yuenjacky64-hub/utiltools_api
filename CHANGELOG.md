# Changelog

## [v0.102.0] - 2026-11-11

- Idempotent submissions (409 CONTACT_IDEMPOTENCY_CONFLICT on payload conflict), deterministic 429 CONTACT_QUEUE_FULL with atomic rollback, best-effort AuditJournalService CONTACT_SUBMIT journalling

