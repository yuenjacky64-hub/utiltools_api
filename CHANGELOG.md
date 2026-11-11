# Changelog

## [v0.98.0] - 2026-11-11

- Failed atomic writes or knowledge-base renders keep records pending for an exactly-once retry, corrupt-file quarantine is serialized to a single winner, and `get_context_for_prompt`/`recent_task_names` refresh when a peer process wrote the file.

