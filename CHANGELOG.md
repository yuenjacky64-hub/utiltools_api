# Changelog

## [v0.118.0] - 2026-11-12

- Add DEAD_LETTERED contact status that quarantines retryable dispatch/lease failures once the attempt budget is exhausted, preserving the original bounded error and closing the attempt's CONTACT_SUBMIT journal entry exactly once with CONTACT_DEAD_LETTERED
- Add POST /api/v1/utils/contact/submissions/{id}/redrive, the only exit from DEAD_LETTERED: extends maxAttempts to attempt+1, requeues with a fresh lease and a new journal entry, and is bounded by utiltools.contact.max-redrives (default 3, non-negative)
- Add a row-preserving admission rollback so a rejected retry/redrive restores the exact prior snapshot, keeps the row and its idempotency mapping, releases only the admission reservation and leaves no orphaned audit entry
- Document DEAD_LETTERED, /redrive, CONTACT_DEAD_LETTERED and utiltools.contact.max-redrives in README and application.properties

