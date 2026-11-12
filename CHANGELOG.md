# Changelog

## [v0.120.0] - 2026-11-12

- Add MaintainerConfig.max_report_files (default 500; overridable from config files and MAINTAINER_MAX_REPORT_FILES) with positive-integer validation in validate().
- Add MaintenanceReporter.prune_cycle_reports(keep=None, dry_run=False): prunes only regular RUN-*.md files, oldest-first by (mtime, name), beyond the newest N; ledgers, lock files and subdirectories are never candidates.
- Call the prune best-effort after every successful write_cycle_report(); an OSError is logged and the new report is never lost.
- Extend the reports CLI with --prune, --keep N and --dry-run, printing kept/removed counts and working without maintenance_report.txt.

