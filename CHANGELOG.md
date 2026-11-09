# Changelog

## [v0.68.0] - 2026-11-09

- ConsistencyService.reconcile_journal() resolves orphan PENDING journal entries against the live store before recover_all()/run_startup_recovery() scan tasks (WRITE_CONFIRMED / WRITE_NOT_APPLIED / REFUSED terminal outcomes; never rewrites a store).
- recover_all() and run_startup_recovery() now report bounded scanned_pending/confirmed/not_applied/refused/failed counters; per-entry journal failures are counted and isolated.
- Regression suite: 60 tests in tests/test_journal_reconciliation.py + tests/test_consistency_recovery.py covering confirmed, not-applied, diverged, malformed, terminal, per-entry-failure and ordering.

