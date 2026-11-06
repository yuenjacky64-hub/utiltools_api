# Changelog

## [v0.31.0] - 2026-11-06

- Add `static/contact.html` + `static/script.js` five-state contact form machine (idle/validating/submitted/success/error) served by Spring Boot static handler.
- Audit every transition to `localStorage` key `utiltools.contact.audit.v1` with `timestamp`, `fromState`, `toState`, `formData` (bounded to 50, in-memory tail survives quota errors).
- Roll back to the pre-submit snapshot on validation failure; failover UI keeps user input and shows per-field errors.
- Prove behaviour with `ContactFormStateMachineTest`, which executes the shipped `script.js` in a Node VM against a DOM shim.

