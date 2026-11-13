# Changelog

All notable changes to the AI Maintainer are recorded here. Releases before
v0.124.0 predate this file on `main`; their notes live on the annotated tags.

## v0.124.0

- Add a per-cycle diagnosis budget (`maintainer.max_diagnosis_budget`, default `3`)
  so recurring failures can no longer drive unbounded diagnosis loops.
- `MaintainerEngine._should_diagnose` now refuses new diagnoses once the budget is
  spent and performs a safe rollback instead of re-entering `DIAGNOSING`.
- Record diagnosis history (`timestamp`, `error_type`, `outcome`, budget state) in
  the cycle report's `issues_detected` list, plus an audit-trail entry when the
  budget is exhausted.
- Reset the diagnosis counter on `OUTCOME_SUCCESS` and note the reset in the cycle
  summary.
