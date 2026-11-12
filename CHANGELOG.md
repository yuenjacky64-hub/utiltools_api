# Changelog

## [v0.114.0] - 2026-11-12

- New: DELETE /api/v1/utils/contact/submissions/{id} — withdraw an admitted contact submission before its worker enters the dispatcher (200 CANCELLED snapshot; 404 CONTACT_SUBMISSION_NOT_FOUND; 409 CONTACT_TRANSITION_CONFLICT for in-flight or terminal rows; repeat cancel of CANCELLED is idempotent 200)
- New: DISPATCHING -> CANCELLED status edge with per-attempt execution-start fence; a fenced late worker never dispatches and never double-releases pendingAttempts; audit entry closed exactly once
- Docs: README endpoint table + contact submission guide document the route with verbatim error codes

