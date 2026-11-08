# Changelog

## [v0.61.0] - 2026-11-08

- New `python3 cli.py check` subcommand: read-only configuration pre-flight (path resolution, free-tier model validation via `is_free_model()`, orchestrator liveness/queue/active/last-completed) with a 0/1 exit contract and descriptive stdout/stderr rows.
- `src/ai_maintainer/checks.py`: tolerant layer resolution across `must_config` / `config.json` / `MAINTAINER_*` env / kwargs; bounded `_signal.txt` reads; every failure surfaces as a `[FAIL]` row instead of a traceback.
- `tests/test_cli.py`: 13 CLI tests covering both exit branches, SKIP/WARN degradation and a no-mutation assertion.
- README: new "Configuration Check" section documenting invocation, check ids, exit contract and sample output.

